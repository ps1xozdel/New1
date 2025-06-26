package eu.siacs.conversations.xmpp.manager;

import android.util.Log;
import com.google.common.base.Strings;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import de.gultsch.common.FutureMerger;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Bookmark;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.MucOptions;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.utils.CryptoHelper;
import eu.siacs.conversations.utils.StringUtils;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.XmppConnection;
import im.conversations.android.xmpp.Entity;
import im.conversations.android.xmpp.IqErrorException;
import im.conversations.android.xmpp.model.Extension;
import im.conversations.android.xmpp.model.data.Data;
import im.conversations.android.xmpp.model.disco.info.InfoQuery;
import im.conversations.android.xmpp.model.error.Condition;
import im.conversations.android.xmpp.model.muc.Affiliation;
import im.conversations.android.xmpp.model.muc.History;
import im.conversations.android.xmpp.model.muc.MultiUserChat;
import im.conversations.android.xmpp.model.muc.Password;
import im.conversations.android.xmpp.model.muc.Role;
import im.conversations.android.xmpp.model.muc.admin.Item;
import im.conversations.android.xmpp.model.muc.admin.MucAdmin;
import im.conversations.android.xmpp.model.muc.owner.Destroy;
import im.conversations.android.xmpp.model.muc.owner.MucOwner;
import im.conversations.android.xmpp.model.pgp.Signed;
import im.conversations.android.xmpp.model.stanza.Iq;
import im.conversations.android.xmpp.model.stanza.Message;
import im.conversations.android.xmpp.model.stanza.Presence;
import im.conversations.android.xmpp.model.vcard.update.VCardUpdate;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;

public class MultiUserChatManager extends AbstractManager {

    private final XmppConnectionService service;

    public MultiUserChatManager(final XmppConnectionService service, XmppConnection connection) {
        super(service.getApplicationContext(), connection);
        this.service = service;
    }

    public ListenableFuture<Void> join(final Conversation conversation) {
        final var account = getAccount();
        synchronized (account.inProgressConferenceJoins) {
            account.inProgressConferenceJoins.add(conversation);
        }
        if (Config.MUC_LEAVE_BEFORE_JOIN) {
            unavailable(conversation);
        }
        // TODO retain flag no push somehow for adhoc creations
        conversation.resetMucOptions();
        conversation.setHasMessagesLeftOnServer(false);
        final var disco = fetchDiscoInfo(conversation);

        final var caughtDisco =
                Futures.catchingAsync(
                        disco,
                        IqErrorException.class,
                        ex -> {
                            if (conversation.getStatus() == Conversation.STATUS_ARCHIVED) {
                                return Futures.immediateFailedFuture(
                                        new IllegalStateException(
                                                "conversation got archived before disco returned"));
                            }
                            Log.d(Config.LOGTAG, "error fetching disco#info", ex);
                            final var iqError = ex.getError();
                            if (iqError != null
                                    && iqError.getCondition()
                                            instanceof Condition.RemoteServerNotFound) {
                                synchronized (account.inProgressConferenceJoins) {
                                    account.inProgressConferenceJoins.remove(conversation);
                                }
                                conversation
                                        .getMucOptions()
                                        .setError(MucOptions.Error.SERVER_NOT_FOUND);
                                service.updateConversationUi();
                                return Futures.immediateFailedFuture(ex);
                            } else {
                                return Futures.immediateFuture(new InfoQuery());
                            }
                        },
                        MoreExecutors.directExecutor());

        return Futures.transform(
                caughtDisco,
                v -> {
                    checkConfigurationSendPresenceFetchHistory(conversation);
                    return null;
                },
                MoreExecutors.directExecutor());
    }

    public ListenableFuture<Void> joinFollowingInvite(final Conversation conversation) {
        // TODO this special treatment is probably unnecessary; just always make sure the bookmark
        // exists
        return Futures.transform(
                join(conversation),
                v -> {
                    // we used to do this only for private groups
                    final Bookmark bookmark = conversation.getBookmark();
                    if (bookmark != null) {
                        if (bookmark.autojoin()) {
                            return null;
                        }
                        bookmark.setAutojoin(true);
                        getManager(BookmarkManager.class).createBookmark(bookmark);
                    } else {
                        getManager(BookmarkManager.class).save(conversation, null);
                    }
                    return null;
                },
                MoreExecutors.directExecutor());
    }

    private void checkConfigurationSendPresenceFetchHistory(final Conversation conversation) {

        Account account = conversation.getAccount();
        final MucOptions mucOptions = conversation.getMucOptions();

        if (mucOptions.nonanonymous()
                && !mucOptions.membersOnly()
                && !conversation.getBooleanAttribute("accept_non_anonymous", false)) {
            synchronized (account.inProgressConferenceJoins) {
                account.inProgressConferenceJoins.remove(conversation);
            }
            mucOptions.setError(MucOptions.Error.NON_ANONYMOUS);
            service.updateConversationUi();
            return;
        }

        final Jid joinJid = mucOptions.getSelf().getFullJid();
        Log.d(
                Config.LOGTAG,
                account.getJid().asBareJid().toString()
                        + ": joining conversation "
                        + joinJid.toString());

        final var x = new MultiUserChat();

        if (mucOptions.getPassword() != null) {
            x.addExtension(new Password(mucOptions.getPassword()));
        }

        final var history = x.addExtension(new History());

        if (mucOptions.mamSupport()) {
            // Use MAM instead of the limited muc history to get history
            history.setMaxStanzas(0);
        } else {
            // Fallback to muc history
            history.setSince(conversation.getLastMessageTransmitted().getTimestamp());
        }
        available(joinJid, mucOptions.nonanonymous(), x);
        if (!joinJid.equals(conversation.getJid())) {
            conversation.setContactJid(joinJid);
            getDatabase().updateConversation(conversation);
        }

        if (mucOptions.mamSupport()) {
            this.service.getMessageArchiveService().catchupMUC(conversation);
        }
        if (mucOptions.isPrivateAndNonAnonymous()) {
            fetchMembers(conversation);
        }
        synchronized (account.inProgressConferenceJoins) {
            account.inProgressConferenceJoins.remove(conversation);
            this.service.sendUnsentMessages(conversation);
        }
    }

    public ListenableFuture<Conversation> createPrivateGroupChat(
            final String name, final Collection<Jid> addresses) {
        final var service = getService();
        if (service == null) {
            return Futures.immediateFailedFuture(new IllegalStateException("No MUC service found"));
        }
        final var address = Jid.ofLocalAndDomain(CryptoHelper.pronounceable(), service);
        final var conversation =
                this.service.findOrCreateConversation(getAccount(), address, true, false, true);
        final var join = this.join(conversation);
        final var configured =
                Futures.transformAsync(
                        join,
                        v -> {
                            final var options =
                                    configWithName(defaultGroupChatConfiguration(), name);
                            return pushConfiguration(conversation, options);
                        },
                        MoreExecutors.directExecutor());

        // TODO add catching to 'configured' to archive the chat again

        return Futures.transform(
                configured,
                c -> {
                    for (var invitee : addresses) {
                        this.service.invite(conversation, invitee);
                    }
                    final var account = getAccount();
                    for (final var resource :
                            account.getSelfContact().getPresences().toResourceArray()) {
                        Jid other = getAccount().getJid().withResource(resource);
                        Log.d(
                                Config.LOGTAG,
                                account.getJid().asBareJid()
                                        + ": sending direct invite to "
                                        + other);
                        this.service.directInvite(conversation, other);
                    }
                    getManager(BookmarkManager.class).save(conversation, name);
                    return conversation;
                },
                MoreExecutors.directExecutor());
    }

    public ListenableFuture<Conversation> createPublicChannel(
            final Jid address, final String name) {

        final var conversation =
                this.service.findOrCreateConversation(getAccount(), address, true, false, true);

        final var join = this.join(conversation);
        final var configuration =
                Futures.transformAsync(
                        join,
                        v -> {
                            final var options = configWithName(defaultChannelConfiguration(), name);
                            return pushConfiguration(conversation, options);
                        },
                        MoreExecutors.directExecutor());

        // TODO mostly ignore configuration error

        return Futures.transform(
                configuration,
                v -> {
                    getManager(BookmarkManager.class).save(conversation, name);
                    return conversation;
                },
                MoreExecutors.directExecutor());
    }

    public void leave(final Conversation conversation) {
        final var mucOptions = conversation.getMucOptions();
        mucOptions.setOffline();
        getManager(DiscoManager.class).clear(conversation.getJid().asBareJid());
        unavailable(conversation);
    }

    public void handlePresence(final Presence presence) {}

    public void handleStatusMessage(final Message message) {}

    public ListenableFuture<Void> fetchDiscoInfo(final Conversation conversation) {
        final var address = conversation.getJid().asBareJid();
        final var future =
                connection.getManager(DiscoManager.class).info(Entity.discoItem(address), null);
        return Futures.transform(
                future,
                infoQuery -> {
                    setDiscoInfo(conversation, infoQuery);
                    return null;
                },
                MoreExecutors.directExecutor());
    }

    private void setDiscoInfo(final Conversation conversation, final InfoQuery result) {
        final var account = conversation.getAccount();
        final var address = conversation.getJid().asBareJid();
        final var avatarHash =
                result.getServiceDiscoveryExtension(
                        Namespace.MUC_ROOM_INFO, "muc#roominfo_avatarhash");
        if (VCardUpdate.isValidSHA1(avatarHash)) {
            connection.getManager(AvatarManager.class).handleVCardUpdate(address, avatarHash);
        }
        final MucOptions mucOptions = conversation.getMucOptions();
        final Bookmark bookmark = conversation.getBookmark();
        final boolean sameBefore =
                StringUtils.equals(
                        bookmark == null ? null : bookmark.getBookmarkName(), mucOptions.getName());

        final var hadOccupantId = mucOptions.occupantId();
        if (mucOptions.updateConfiguration(result)) {
            Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid()
                            + ": muc configuration changed for "
                            + conversation.getJid().asBareJid());
            getDatabase().updateConversation(conversation);
        }

        final var hasOccupantId = mucOptions.occupantId();

        if (!hadOccupantId && hasOccupantId && mucOptions.online()) {
            final var me = mucOptions.getSelf().getFullJid();
            Log.d(
                    Config.LOGTAG,
                    account.getJid().asBareJid()
                            + ": gained support for occupant-id in "
                            + me
                            + ". resending presence");
            this.available(me, mucOptions.nonanonymous());
        }

        if (bookmark != null && (sameBefore || bookmark.getBookmarkName() == null)) {
            if (bookmark.setBookmarkName(StringUtils.nullOnEmpty(mucOptions.getName()))) {
                this.service.createBookmark(account, bookmark);
            }
        }
        this.service.updateConversationUi();
    }

    public void resendPresence(final Conversation conversation) {
        final MucOptions mucOptions = conversation.getMucOptions();
        if (mucOptions.online()) {
            available(mucOptions.getSelf().getFullJid(), mucOptions.nonanonymous());
        }
    }

    private void available(
            final Jid address, final boolean nonAnonymous, final Extension... extensions) {
        final var presenceManager = getManager(PresenceManager.class);
        final var account = getAccount();
        final String pgpSignature = account.getPgpSignature();
        if (nonAnonymous && pgpSignature != null) {
            final String message = account.getPresenceStatusMessage();
            presenceManager.available(
                    address, message, combine(extensions, new Signed(pgpSignature)));
        } else {
            presenceManager.available(address, extensions);
        }
    }

    public void unavailable(final Conversation conversation) {
        final var mucOptions = conversation.getMucOptions();
        getManager(PresenceManager.class).unavailable(mucOptions.getSelf().getFullJid());
    }

    private static Extension[] combine(final Extension[] extensions, final Extension extension) {
        return new ImmutableList.Builder<Extension>()
                .addAll(Arrays.asList(extensions))
                .add(extension)
                .build()
                .toArray(new Extension[0]);
    }

    public ListenableFuture<Void> pushConfiguration(
            final Conversation conversation, final Map<String, Object> input) {
        final var address = conversation.getJid().asBareJid();
        final var configuration = modifyBestInteroperability(input);

        if (configuration.get("muc#roomconfig_whois") instanceof String whois
                && whois.equals("anyone")) {
            conversation.setAttribute("accept_non_anonymous", true);
            getDatabase().updateConversation(conversation);
        }

        final var future = fetchConfigurationForm(address);
        return Futures.transformAsync(
                future,
                current -> {
                    final var modified = current.submit(configuration);
                    return submitConfigurationForm(address, modified);
                },
                MoreExecutors.directExecutor());
    }

    public ListenableFuture<Data> fetchConfigurationForm(final Jid address) {
        final var iq = new Iq(Iq.Type.GET, new MucOwner());
        iq.setTo(address);
        Log.d(Config.LOGTAG, "fetching configuration form: " + iq);
        return Futures.transform(
                connection.sendIqPacket(iq),
                response -> {
                    final var mucOwner = response.getExtension(MucOwner.class);
                    if (mucOwner == null) {
                        throw new IllegalStateException("Missing MucOwner element in response");
                    }
                    return mucOwner.getConfiguration();
                },
                MoreExecutors.directExecutor());
    }

    private ListenableFuture<Void> submitConfigurationForm(final Jid address, final Data data) {
        final var iq = new Iq(Iq.Type.SET);
        iq.setTo(address);
        final var mucOwner = iq.addExtension(new MucOwner());
        mucOwner.addExtension(data);
        Log.d(Config.LOGTAG, "pushing configuration form: " + iq);
        return Futures.transform(
                this.connection.sendIqPacket(iq), response -> null, MoreExecutors.directExecutor());
    }

    public ListenableFuture<Void> fetchMembers(final Conversation conversation) {
        final var futures =
                Collections2.transform(
                        Arrays.asList(Affiliation.OWNER, Affiliation.ADMIN, Affiliation.MEMBER),
                        a -> fetchAffiliations(conversation, a));
        ListenableFuture<List<MucOptions.User>> future = FutureMerger.allAsList(futures);
        return Futures.transform(
                future,
                members -> {
                    setMembers(conversation, members);
                    return null;
                },
                MoreExecutors.directExecutor());
    }

    private void setMembers(final Conversation conversation, final List<MucOptions.User> users) {
        final boolean omemoEnabled =
                conversation.getNextEncryption()
                        == eu.siacs.conversations.entities.Message.ENCRYPTION_AXOLOTL;
        final var axolotlService = connection.getAxolotlService();
        for (final var user : users) {
            if (user.realJidMatchesAccount()) {
                continue;
            }
            boolean isNew = conversation.getMucOptions().updateUser(user);
            Contact contact = user.getContact();
            if (omemoEnabled
                    && isNew
                    && user.getRealJid() != null
                    && (contact == null || !contact.mutualPresenceSubscription())
                    && axolotlService.hasEmptyDeviceList(user.getRealJid())) {
                axolotlService.fetchDeviceIds(user.getRealJid());
            }
        }
        final var mucOptions = conversation.getMucOptions();
        final var members = mucOptions.getMembers(true);
        List<Jid> cryptoTargets = conversation.getAcceptedCryptoTargets();
        boolean changed = false;
        for (ListIterator<Jid> iterator = cryptoTargets.listIterator(); iterator.hasNext(); ) {
            Jid jid = iterator.next();
            if (!members.contains(jid) && !members.contains(jid.getDomain())) {
                iterator.remove();
                Log.d(
                        Config.LOGTAG,
                        getAccount().getJid().asBareJid()
                                + ": removed "
                                + jid
                                + " from crypto targets of "
                                + conversation.getName());
                changed = true;
            }
        }
        if (changed) {
            conversation.setAcceptedCryptoTargets(cryptoTargets);
            getDatabase().updateConversation(conversation);
        }
        this.service.getAvatarService().clear(mucOptions);
        this.service.updateMucRosterUi();
        this.service.updateConversationUi();
    }

    private ListenableFuture<Collection<MucOptions.User>> fetchAffiliations(
            final Conversation conversation, final Affiliation affiliation) {
        final var iq = new Iq(Iq.Type.GET);
        iq.setTo(conversation.getJid().asBareJid());
        iq.addExtension(new MucAdmin()).addExtension(new Item()).setAffiliation(affiliation);
        return Futures.transform(
                this.connection.sendIqPacket(iq),
                response -> {
                    final var mucAdmin = response.getExtension(MucAdmin.class);
                    if (mucAdmin == null) {
                        throw new IllegalStateException("No query in response");
                    }
                    return Collections2.transform(
                            mucAdmin.getItems(), i -> itemToUser(conversation, i, null));
                },
                MoreExecutors.directExecutor());
    }

    public ListenableFuture<Void> changeUsername(
            final Conversation conversation, final String username) {

        // TODO when online send normal available presence
        // TODO when not online do a normal join

        final Bookmark bookmark = conversation.getBookmark();
        final MucOptions options = conversation.getMucOptions();
        final Jid joinJid = options.createJoinJid(username);
        if (joinJid == null) {
            return Futures.immediateFailedFuture(new IllegalArgumentException());
        }

        if (options.online()) {
            final SettableFuture<Void> renameFuture = SettableFuture.create();
            options.setOnRenameListener(
                    new MucOptions.OnRenameListener() {

                        @Override
                        public void onSuccess() {
                            renameFuture.set(null);
                        }

                        @Override
                        public void onFailure() {
                            renameFuture.setException(new IllegalStateException());
                        }
                    });

            available(joinJid, options.nonanonymous());

            if (username.equals(MucOptions.defaultNick(getAccount()))
                    && bookmark != null
                    && bookmark.getNick() != null) {
                Log.d(
                        Config.LOGTAG,
                        getAccount().getJid().asBareJid()
                                + ": removing nick from bookmark for "
                                + bookmark.getJid());
                bookmark.setNick(null);
                getManager(BookmarkManager.class).createBookmark(bookmark);
            }
            return renameFuture;
        } else {
            conversation.setContactJid(joinJid);
            getDatabase().updateConversation(conversation);
            if (bookmark != null) {
                bookmark.setNick(username);
                getManager(BookmarkManager.class).createBookmark(bookmark);
            }
            join(conversation);
            return Futures.immediateVoidFuture();
        }
    }

    public void checkMucRequiresRename(final Conversation conversation) {
        final var options = conversation.getMucOptions();
        if (!options.online()) {
            return;
        }
        final String current = options.getActualNick();
        final String proposed = options.getProposedNickPure();
        if (current == null || current.equals(proposed)) {
            return;
        }
        final Jid joinJid = options.createJoinJid(proposed);
        Log.d(
                Config.LOGTAG,
                String.format(
                        "%s: muc rename required %s (was: %s)",
                        getAccount().getJid().asBareJid(), joinJid, current));
        available(joinJid, options.nonanonymous());
    }

    public void setPassword() {}

    public void pingAndRejoin() {}

    public ListenableFuture<Void> destroy(final Jid address) {
        final var iq = new Iq(Iq.Type.SET);
        iq.setTo(address);
        final var mucOwner = iq.addExtension(new MucOwner());
        mucOwner.addExtension(new Destroy());
        return Futures.transform(
                connection.sendIqPacket(iq), result -> null, MoreExecutors.directExecutor());
    }

    public ListenableFuture<Void> setAffiliation(
            final Conversation conversation, final Affiliation affiliation, Jid user) {
        return setAffiliation(conversation, affiliation, Collections.singleton(user));
    }

    public ListenableFuture<Void> setAffiliation(
            final Conversation conversation,
            final Affiliation affiliation,
            final Collection<Jid> users) {
        final var address = conversation.getJid().asBareJid();
        final var iq = new Iq(Iq.Type.SET);
        iq.setTo(address);
        final var admin = iq.addExtension(new MucAdmin());
        for (final var user : users) {
            final var item = admin.addExtension(new Item());
            item.setJid(user);
            item.setAffiliation(affiliation);
        }
        return Futures.transform(
                this.connection.sendIqPacket(iq),
                response -> {
                    // TODO figure out what this was meant to do
                    // is this a work around for some servers not sending notifications when
                    // changing the affiliation of people not in the room? this would explain this
                    // firing only when getRole == None
                    final var mucOptions = conversation.getMucOptions();
                    for (final var user : users) {
                        mucOptions.changeAffiliation(user, affiliation);
                    }
                    service.getAvatarService().clear(mucOptions);
                    return null;
                },
                MoreExecutors.directExecutor());
    }

    public ListenableFuture<Void> setRole(final Jid address, final Role role, final String user) {
        return setRole(address, role, Collections.singleton(user));
    }

    public ListenableFuture<Void> setRole(
            final Jid address, final Role role, final Collection<String> users) {
        final var iq = new Iq(Iq.Type.SET);
        iq.setTo(address);
        final var admin = iq.addExtension(new MucAdmin());
        for (final var user : users) {
            final var item = admin.addExtension(new Item());
            item.setNick(user);
            item.setRole(role);
        }
        return Futures.transform(
                this.connection.sendIqPacket(iq), response -> null, MoreExecutors.directExecutor());
    }

    public Jid getService() {
        return Iterables.getFirst(this.getServices(), null);
    }

    public List<Jid> getServices() {
        final var builder = new ImmutableList.Builder<Jid>();
        for (final var entry : getManager(DiscoManager.class).getServerItems().entrySet()) {
            final var value = entry.getValue();
            if (value.getFeatureStrings().contains("http://jabber.org/protocol/muc")
                    && value.hasIdentityWithCategoryAndType("conference", "text")
                    && !value.getFeatureStrings().contains("jabber:iq:gateway")
                    && !value.hasIdentityWithCategoryAndType("conference", "irc")) {
                builder.add(entry.getKey());
            }
        }
        return builder.build();
    }

    public static MucOptions.User itemToUser(
            final Conversation conference,
            im.conversations.android.xmpp.model.muc.Item item,
            final Jid from) {
        final var affiliation = item.getAffiliation();
        final var role = item.getRole();
        final var nick = item.getNick();
        final Jid fullAddress;
        if (from != null && from.isFullJid()) {
            fullAddress = from;
        } else if (Strings.isNullOrEmpty(nick)) {
            fullAddress = null;
        } else {
            fullAddress = ofNick(conference, nick);
        }
        final Jid realJid = item.getAttributeAsJid("jid");
        MucOptions.User user = new MucOptions.User(conference.getMucOptions(), fullAddress);
        if (Jid.Invalid.isValid(realJid)) {
            user.setRealJid(realJid);
        }
        user.setAffiliation(affiliation);
        user.setRole(role);
        return user;
    }

    private static Jid ofNick(final Conversation conversation, final String nick) {
        try {
            return conversation.getJid().withResource(nick);
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }

    private static Map<String, Object> modifyBestInteroperability(
            final Map<String, Object> unmodified) {
        final var builder = new ImmutableMap.Builder<String, Object>();
        builder.putAll(unmodified);

        if (unmodified.get("muc#roomconfig_moderatedroom") instanceof Boolean moderated) {
            builder.put("members_by_default", !moderated);
        }
        if (unmodified.get("muc#roomconfig_allowpm") instanceof String allowPm) {
            // ejabberd :-/
            final boolean allow = "anyone".equals(allowPm);
            builder.put("allow_private_messages", allow);
            builder.put("allow_private_messages_from_visitors", allow ? "anyone" : "nobody");
        }

        if (unmodified.get("muc#roomconfig_allowinvites") instanceof Boolean allowInvites) {
            // TODO check that this actually does something useful?
            builder.put(
                    "{http://prosody.im/protocol/muc}roomconfig_allowmemberinvites", allowInvites);
        }

        return builder.buildOrThrow();
    }

    private static Map<String, Object> configWithName(
            final Map<String, Object> unmodified, final String name) {
        if (Strings.isNullOrEmpty(name)) {
            return unmodified;
        }
        return new ImmutableMap.Builder<String, Object>()
                .putAll(unmodified)
                .put("muc#roomconfig_roomname", name)
                .buildKeepingLast();
    }

    public static Map<String, Object> defaultGroupChatConfiguration() {
        return new ImmutableMap.Builder<String, Object>()
                .put("muc#roomconfig_persistentroom", true)
                .put("muc#roomconfig_membersonly", true)
                .put("muc#roomconfig_publicroom", false)
                .put("muc#roomconfig_whois", "anyone")
                .put("muc#roomconfig_changesubject", false)
                .put("muc#roomconfig_allowinvites", false)
                .put("muc#roomconfig_enablearchiving", true) // prosody
                .put("mam", true) // ejabberd community
                .put("muc#roomconfig_mam", true) // ejabberd saas
                .buildOrThrow();
    }

    public static Map<String, Object> defaultChannelConfiguration() {
        return new ImmutableMap.Builder<String, Object>()
                .put("muc#roomconfig_persistentroom", true)
                .put("muc#roomconfig_membersonly", false)
                .put("muc#roomconfig_publicroom", true)
                .put("muc#roomconfig_whois", "moderators")
                .put("muc#roomconfig_changesubject", false)
                .put("muc#roomconfig_enablearchiving", true) // prosody
                .put("mam", true) // ejabberd community
                .put("muc#roomconfig_mam", true) // ejabberd saas
                .buildOrThrow();
    }
}
