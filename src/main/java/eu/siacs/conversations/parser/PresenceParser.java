package eu.siacs.conversations.parser;

import android.util.Log;
import androidx.annotation.NonNull;
import com.google.common.base.Strings;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.crypto.PgpEngine;
import eu.siacs.conversations.crypto.axolotl.AxolotlService;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.entities.MucOptions;
import eu.siacs.conversations.generator.PresenceGenerator;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.utils.XmppUri;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.XmppConnection;
import eu.siacs.conversations.xmpp.manager.AvatarManager;
import eu.siacs.conversations.xmpp.manager.DiscoManager;
import eu.siacs.conversations.xmpp.manager.MultiUserChatManager;
import eu.siacs.conversations.xmpp.manager.PresenceManager;
import eu.siacs.conversations.xmpp.manager.RosterManager;
import im.conversations.android.xmpp.Entity;
import im.conversations.android.xmpp.model.muc.user.MucUser;
import im.conversations.android.xmpp.model.occupant.OccupantId;
import im.conversations.android.xmpp.model.vcard.update.VCardUpdate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.openintents.openpgp.util.OpenPgpUtils;

public class PresenceParser extends AbstractParser
        implements Consumer<im.conversations.android.xmpp.model.stanza.Presence> {

    public PresenceParser(final XmppConnectionService service, final XmppConnection connection) {
        super(service, connection);
    }

    public void parseConferencePresence(
            final im.conversations.android.xmpp.model.stanza.Presence packet) {
        final var account = getAccount();
        final Conversation conversation =
                packet.getFrom() == null
                        ? null
                        : mXmppConnectionService.find(account, packet.getFrom().asBareJid());
        if (conversation == null) {
            Log.d(Config.LOGTAG, "conversation not found for parsing conference presence");
            return;
        }
        final MucOptions mucOptions = conversation.getMucOptions();
        boolean before = mucOptions.online();
        int count = mucOptions.getUserCount();
        final List<MucOptions.User> tileUserBefore = mucOptions.getUsers(5);
        processConferencePresence(packet, conversation);
        final List<MucOptions.User> tileUserAfter = mucOptions.getUsers(5);
        if (Strings.isNullOrEmpty(mucOptions.getAvatar())
                && !tileUserAfter.equals(tileUserBefore)) {
            mXmppConnectionService.getAvatarService().clear(mucOptions);
        }
        if (before != mucOptions.online()
                || (mucOptions.online() && count != mucOptions.getUserCount())) {
            mXmppConnectionService.updateConversationUi();
        } else if (mucOptions.online()) {
            mXmppConnectionService.updateMucRosterUi();
        }
    }

    private void processConferencePresence(
            final im.conversations.android.xmpp.model.stanza.Presence packet,
            Conversation conversation) {
        final Account account = conversation.getAccount();
        final MucOptions mucOptions = conversation.getMucOptions();
        final Jid jid = conversation.getAccount().getJid();
        final Jid from = packet.getFrom();
        if (!from.isBareJid()) {
            final String type = packet.getAttribute("type");
            final var x = packet.getExtension(MucUser.class);
            final var vCardUpdate = packet.getExtension(VCardUpdate.class);
            final List<String> codes = getStatusCodes(x);
            if (type == null) {
                if (x != null) {
                    final var item = x.getItem();
                    if (item != null && !from.isBareJid()) {
                        mucOptions.setError(MucOptions.Error.NONE);
                        final MucOptions.User user =
                                MultiUserChatManager.itemToUser(conversation, item, from);
                        final var occupant = packet.getOnlyExtension(OccupantId.class);
                        final String occupantId =
                                mucOptions.occupantId() && occupant != null
                                        ? occupant.getId()
                                        : null;
                        user.setOccupantId(occupantId);
                        if (codes.contains(MucOptions.STATUS_CODE_SELF_PRESENCE)
                                || (codes.contains(MucOptions.STATUS_CODE_ROOM_CREATED)
                                        && jid.equals(
                                                Jid.Invalid.getNullForInvalid(
                                                        item.getAttributeAsJid("jid"))))) {
                            Log.d(
                                    Config.LOGTAG,
                                    account.getJid().asBareJid()
                                            + ": got self-presence from "
                                            + user.getFullJid()
                                            + ". occupant-id="
                                            + occupantId);
                            if (mucOptions.setOnline()) {
                                mXmppConnectionService.getAvatarService().clear(mucOptions);
                            }
                            final var current = mucOptions.getSelf().getFullJid();
                            if (mucOptions.setSelf(user)) {
                                Log.d(Config.LOGTAG, "role or affiliation changed");
                                mXmppConnectionService.databaseBackend.updateConversation(
                                        conversation);
                            }
                            final var modified =
                                    current == null || !current.equals(user.getFullJid());
                            mXmppConnectionService.persistSelfNick(user, modified);
                            invokeRenameListener(mucOptions, true);
                        }
                        boolean isNew = mucOptions.updateUser(user);
                        final AxolotlService axolotlService =
                                conversation.getAccount().getAxolotlService();
                        Contact contact = user.getContact();
                        if (isNew
                                && user.getRealJid() != null
                                && mucOptions.isPrivateAndNonAnonymous()
                                && (contact == null || !contact.mutualPresenceSubscription())
                                && axolotlService.hasEmptyDeviceList(user.getRealJid())) {
                            axolotlService.fetchDeviceIds(user.getRealJid());
                        }
                        if (codes.contains(MucOptions.STATUS_CODE_ROOM_CREATED)
                                && mucOptions.autoPushConfiguration()) {
                            final var address = mucOptions.getConversation().getJid().asBareJid();
                            Log.d(
                                    Config.LOGTAG,
                                    account.getJid().asBareJid()
                                            + ": room '"
                                            + address
                                            + "' created. pushing default configuration");
                            getManager(MultiUserChatManager.class)
                                    .pushConfiguration(
                                            conversation,
                                            MultiUserChatManager.defaultChannelConfiguration());
                        }
                        if (mXmppConnectionService.getPgpEngine() != null) {
                            Element signed = packet.findChild("x", "jabber:x:signed");
                            if (signed != null) {
                                Element status = packet.findChild("status");
                                String msg = status == null ? "" : status.getContent();
                                long keyId =
                                        mXmppConnectionService
                                                .getPgpEngine()
                                                .fetchKeyId(
                                                        mucOptions.getAccount(),
                                                        msg,
                                                        signed.getContent());
                                if (keyId != 0) {
                                    user.setPgpKeyId(keyId);
                                }
                            }
                        }
                        if (vCardUpdate != null) {
                            getManager(AvatarManager.class).handleVCardUpdate(from, vCardUpdate);
                        }
                    }
                }
            } else if (type.equals("unavailable")) {
                final boolean fullJidMatches = from.equals(mucOptions.getSelf().getFullJid());
                if (x.hasChild("destroy") && fullJidMatches) {
                    Element destroy = x.findChild("destroy");
                    final Jid alternate =
                            destroy == null
                                    ? null
                                    : Jid.Invalid.getNullForInvalid(
                                            destroy.getAttributeAsJid("jid"));
                    mucOptions.setError(MucOptions.Error.DESTROYED);
                    if (alternate != null) {
                        Log.d(
                                Config.LOGTAG,
                                account.getJid().asBareJid()
                                        + ": muc destroyed. alternate location "
                                        + alternate);
                    }
                } else if (codes.contains(MucOptions.STATUS_CODE_SHUTDOWN) && fullJidMatches) {
                    mucOptions.setError(MucOptions.Error.SHUTDOWN);
                } else if (codes.contains(MucOptions.STATUS_CODE_SELF_PRESENCE)) {
                    if (codes.contains(MucOptions.STATUS_CODE_TECHNICAL_REASONS)) {
                        final boolean wasOnline = mucOptions.online();
                        mucOptions.setError(MucOptions.Error.TECHNICAL_PROBLEMS);
                        Log.d(
                                Config.LOGTAG,
                                account.getJid().asBareJid()
                                        + ": received status code 333 in room "
                                        + mucOptions.getConversation().getJid().asBareJid()
                                        + " online="
                                        + wasOnline);
                        if (wasOnline) {
                            mXmppConnectionService.mucSelfPingAndRejoin(conversation);
                        }
                    } else if (codes.contains(MucOptions.STATUS_CODE_KICKED)) {
                        mucOptions.setError(MucOptions.Error.KICKED);
                    } else if (codes.contains(MucOptions.STATUS_CODE_BANNED)) {
                        mucOptions.setError(MucOptions.Error.BANNED);
                    } else if (codes.contains(MucOptions.STATUS_CODE_LOST_MEMBERSHIP)) {
                        mucOptions.setError(MucOptions.Error.MEMBERS_ONLY);
                    } else if (codes.contains(MucOptions.STATUS_CODE_AFFILIATION_CHANGE)) {
                        mucOptions.setError(MucOptions.Error.MEMBERS_ONLY);
                    } else if (codes.contains(MucOptions.STATUS_CODE_SHUTDOWN)) {
                        mucOptions.setError(MucOptions.Error.SHUTDOWN);
                    } else if (!codes.contains(MucOptions.STATUS_CODE_CHANGED_NICK)) {
                        mucOptions.setError(MucOptions.Error.UNKNOWN);
                        Log.d(Config.LOGTAG, "unknown error in conference: " + packet);
                    }
                } else if (!from.isBareJid()) {
                    final var item = x.getItem();
                    if (item != null) {
                        mucOptions.updateUser(
                                MultiUserChatManager.itemToUser(conversation, item, from));
                    }
                    MucOptions.User user = mucOptions.deleteUser(from);
                    if (user != null) {
                        mXmppConnectionService.getAvatarService().clear(user);
                    }
                }
            } else if (type.equals("error")) {
                final Element error = packet.findChild("error");
                if (error == null) {
                    return;
                }
                if (error.hasChild("conflict")) {
                    if (mucOptions.online()) {
                        invokeRenameListener(mucOptions, false);
                    } else {
                        mucOptions.setError(MucOptions.Error.NICK_IN_USE);
                    }
                } else if (error.hasChild("not-authorized")) {
                    mucOptions.setError(MucOptions.Error.PASSWORD_REQUIRED);
                } else if (error.hasChild("forbidden")) {
                    mucOptions.setError(MucOptions.Error.BANNED);
                } else if (error.hasChild("registration-required")) {
                    mucOptions.setError(MucOptions.Error.MEMBERS_ONLY);
                } else if (error.hasChild("resource-constraint")) {
                    mucOptions.setError(MucOptions.Error.RESOURCE_CONSTRAINT);
                } else if (error.hasChild("remote-server-timeout")) {
                    mucOptions.setError(MucOptions.Error.REMOTE_SERVER_TIMEOUT);
                } else if (error.hasChild("gone")) {
                    final String gone = error.findChildContent("gone");
                    final Jid alternate;
                    if (gone != null) {
                        final XmppUri xmppUri = new XmppUri(gone);
                        if (xmppUri.isValidJid()) {
                            alternate = xmppUri.getJid();
                        } else {
                            alternate = null;
                        }
                    } else {
                        alternate = null;
                    }
                    mucOptions.setError(MucOptions.Error.DESTROYED);
                    if (alternate != null) {
                        Log.d(
                                Config.LOGTAG,
                                conversation.getAccount().getJid().asBareJid()
                                        + ": muc destroyed. alternate location "
                                        + alternate);
                    }
                } else {
                    final String text = error.findChildContent("text");
                    if (text != null && text.contains("attribute 'to'")) {
                        if (mucOptions.online()) {
                            invokeRenameListener(mucOptions, false);
                        } else {
                            mucOptions.setError(MucOptions.Error.INVALID_NICK);
                        }
                    } else {
                        mucOptions.setError(MucOptions.Error.UNKNOWN);
                        Log.d(Config.LOGTAG, "unknown error in conference: " + packet);
                    }
                }
            }
        }
    }

    private static void invokeRenameListener(final MucOptions options, boolean success) {
        if (options.onRenameListener != null) {
            if (success) {
                options.onRenameListener.onSuccess();
            } else {
                options.onRenameListener.onFailure();
            }
            options.onRenameListener = null;
        }
    }

    private static List<String> getStatusCodes(Element x) {
        List<String> codes = new ArrayList<>();
        if (x != null) {
            for (Element child : x.getChildren()) {
                if (child.getName().equals("status")) {
                    String code = child.getAttribute("code");
                    if (code != null) {
                        codes.add(code);
                    }
                }
            }
        }
        return codes;
    }

    private void parseContactPresence(
            final im.conversations.android.xmpp.model.stanza.Presence packet) {
        final var account = getAccount();
        final PresenceGenerator mPresenceGenerator = mXmppConnectionService.getPresenceGenerator();
        final Jid from = packet.getFrom();
        if (from == null || from.equals(account.getJid())) {
            return;
        }
        final String type = packet.getAttribute("type");
        final Contact contact = account.getRoster().getContact(from);
        if (type == null) {
            final String resource = from.isBareJid() ? "" : from.getResource();

            if (mXmppConnectionService.isMuc(account, from)) {
                return;
            }

            final int sizeBefore = contact.getPresences().size();

            contact.updatePresence(resource, packet);

            final var nodeHash = packet.getCapabilities();
            if (nodeHash != null) {
                final var discoFuture =
                        this.getManager(DiscoManager.class)
                                .infoOrCache(Entity.presence(from), nodeHash.node, nodeHash.hash);

                logDiscoFailure(from, discoFuture);
            }

            final Element idle = packet.findChild("idle", Namespace.IDLE);
            if (idle != null) {
                try {
                    final String since = idle.getAttribute("since");
                    contact.setLastseen(AbstractParser.parseTimestamp(since));
                    contact.flagInactive();
                } catch (Throwable throwable) {
                    if (contact.setLastseen(AbstractParser.parseTimestamp(packet))) {
                        contact.flagActive();
                    }
                }
            } else {
                if (contact.setLastseen(AbstractParser.parseTimestamp(packet))) {
                    contact.flagActive();
                }
            }

            final PgpEngine pgp = mXmppConnectionService.getPgpEngine();
            final Element x = packet.findChild("x", "jabber:x:signed");
            if (pgp != null && x != null) {
                final String status = packet.findChildContent("status");
                final long keyId = pgp.fetchKeyId(account, status, x.getContent());
                if (keyId != 0 && contact.setPgpKeyId(keyId)) {
                    Log.d(
                            Config.LOGTAG,
                            account.getJid().asBareJid()
                                    + ": found OpenPGP key id for "
                                    + contact.getJid()
                                    + " "
                                    + OpenPgpUtils.convertKeyIdToHex(keyId));
                    this.connection.getManager(RosterManager.class).writeToDatabaseAsync();
                }
            }
            boolean online = sizeBefore < contact.getPresences().size();
            mXmppConnectionService.onContactStatusChanged.onContactStatusChanged(contact, online);
        } else if (type.equals("unavailable")) {
            if (contact.setLastseen(AbstractParser.parseTimestamp(packet, 0L, true))) {
                contact.flagInactive();
            }
            getManager(DiscoManager.class).clear(from);
            if (from.isBareJid()) {
                contact.clearPresences();
            } else {
                contact.removePresence(from.getResource());
            }
            if (contact.getShownStatus()
                    == im.conversations.android.xmpp.model.stanza.Presence.Availability.OFFLINE) {
                contact.flagInactive();
            }
            mXmppConnectionService.onContactStatusChanged.onContactStatusChanged(contact, false);
        } else if (type.equals("subscribe")) {
            if (contact.isBlocked()) {
                Log.d(
                        Config.LOGTAG,
                        account.getJid().asBareJid()
                                + ": ignoring 'subscribe' presence from blocked "
                                + from);
                return;
            }
            if (contact.setPresenceName(packet.findChildContent("nick", Namespace.NICK))) {
                this.getManager(RosterManager.class).writeToDatabaseAsync();
                mXmppConnectionService.getAvatarService().clear(contact);
            }
            if (contact.getOption(Contact.Options.PREEMPTIVE_GRANT)) {
                connection
                        .getManager(PresenceManager.class)
                        .subscribed(contact.getJid().asBareJid());
            } else {
                contact.setOption(Contact.Options.PENDING_SUBSCRIPTION_REQUEST);
                final Conversation conversation =
                        mXmppConnectionService.findOrCreateConversation(
                                account, contact.getJid().asBareJid(), false, false);
                final String statusMessage = packet.findChildContent("status");
                if (statusMessage != null
                        && !statusMessage.isEmpty()
                        && conversation.countMessages() == 0) {
                    conversation.add(
                            new Message(
                                    conversation,
                                    statusMessage,
                                    Message.ENCRYPTION_NONE,
                                    Message.STATUS_RECEIVED));
                }
            }
        }
        mXmppConnectionService.updateRosterUi();
    }

    private static void logDiscoFailure(final Jid from, ListenableFuture<Void> discoFuture) {
        Futures.addCallback(
                discoFuture,
                new FutureCallback<>() {
                    @Override
                    public void onSuccess(Void result) {}

                    @Override
                    public void onFailure(@NonNull Throwable throwable) {
                        if (throwable instanceof TimeoutException) {
                            return;
                        }
                        Log.d(Config.LOGTAG, "could not retrieve disco from " + from, throwable);
                    }
                },
                MoreExecutors.directExecutor());
    }

    @Override
    public void accept(final im.conversations.android.xmpp.model.stanza.Presence packet) {
        if (packet.hasChild("x", Namespace.MUC_USER)) {
            this.parseConferencePresence(packet);
        } else if (packet.hasChild("x", "http://jabber.org/protocol/muc")) {
            this.parseConferencePresence(packet);
        } else if ("error".equals(packet.getAttribute("type"))
                && mXmppConnectionService.isMuc(getAccount(), packet.getFrom())) {
            this.parseConferencePresence(packet);
        } else {
            this.parseContactPresence(packet);
        }
    }
}
