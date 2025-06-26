package eu.siacs.conversations.generator;

import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.crypto.axolotl.AxolotlService;
import eu.siacs.conversations.services.MessageArchiveService;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.Jid;
import eu.siacs.conversations.xmpp.forms.Data;
import im.conversations.android.xmpp.model.stanza.Iq;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.Set;
import org.whispersystems.libsignal.IdentityKey;
import org.whispersystems.libsignal.ecc.ECPublicKey;
import org.whispersystems.libsignal.state.PreKeyRecord;
import org.whispersystems.libsignal.state.SignedPreKeyRecord;

public class IqGenerator extends AbstractGenerator {

    public IqGenerator(final XmppConnectionService service) {
        super(service);
    }

    protected Iq publish(final String node, final Element item, final Bundle options) {
        final var packet = new Iq(Iq.Type.SET);
        final Element pubsub = packet.addChild("pubsub", Namespace.PUBSUB);
        final Element publish = pubsub.addChild("publish");
        publish.setAttribute("node", node);
        publish.addChild(item);
        if (options != null) {
            final Element publishOptions = pubsub.addChild("publish-options");
            publishOptions.addChild(Data.create(Namespace.PUBSUB_PUBLISH_OPTIONS, options));
        }
        return packet;
    }

    protected Iq publish(final String node, final Element item) {
        return publish(node, item, null);
    }

    private Iq retrieve(String node, Element item) {
        final var packet = new Iq(Iq.Type.GET);
        final Element pubsub = packet.addChild("pubsub", Namespace.PUBSUB);
        final Element items = pubsub.addChild("items");
        items.setAttribute("node", node);
        if (item != null) {
            items.addChild(item);
        }
        return packet;
    }

    public Iq retrieveDeviceIds(final Jid to) {
        final var packet = retrieve(AxolotlService.PEP_DEVICE_LIST, null);
        if (to != null) {
            packet.setTo(to);
        }
        return packet;
    }

    public Iq retrieveBundlesForDevice(final Jid to, final int deviceid) {
        final var packet = retrieve(AxolotlService.PEP_BUNDLES + ":" + deviceid, null);
        packet.setTo(to);
        return packet;
    }

    public Iq retrieveVerificationForDevice(final Jid to, final int deviceid) {
        final var packet = retrieve(AxolotlService.PEP_VERIFICATION + ":" + deviceid, null);
        packet.setTo(to);
        return packet;
    }

    public Iq publishDeviceIds(final Set<Integer> ids, final Bundle publishOptions) {
        final Element item = new Element("item");
        item.setAttribute("id", "current");
        final Element list = item.addChild("list", AxolotlService.PEP_PREFIX);
        for (Integer id : ids) {
            final Element device = new Element("device");
            device.setAttribute("id", id);
            list.addChild(device);
        }
        return publish(AxolotlService.PEP_DEVICE_LIST, item, publishOptions);
    }

    public Iq publishBundles(
            final SignedPreKeyRecord signedPreKeyRecord,
            final IdentityKey identityKey,
            final Set<PreKeyRecord> preKeyRecords,
            final int deviceId,
            Bundle publishOptions) {
        final Element item = new Element("item");
        item.setAttribute("id", "current");
        final Element bundle = item.addChild("bundle", AxolotlService.PEP_PREFIX);
        final Element signedPreKeyPublic = bundle.addChild("signedPreKeyPublic");
        signedPreKeyPublic.setAttribute("signedPreKeyId", signedPreKeyRecord.getId());
        ECPublicKey publicKey = signedPreKeyRecord.getKeyPair().getPublicKey();
        signedPreKeyPublic.setContent(Base64.encodeToString(publicKey.serialize(), Base64.NO_WRAP));
        final Element signedPreKeySignature = bundle.addChild("signedPreKeySignature");
        signedPreKeySignature.setContent(
                Base64.encodeToString(signedPreKeyRecord.getSignature(), Base64.NO_WRAP));
        final Element identityKeyElement = bundle.addChild("identityKey");
        identityKeyElement.setContent(
                Base64.encodeToString(identityKey.serialize(), Base64.NO_WRAP));

        final Element prekeys = bundle.addChild("prekeys", AxolotlService.PEP_PREFIX);
        for (PreKeyRecord preKeyRecord : preKeyRecords) {
            final Element prekey = prekeys.addChild("preKeyPublic");
            prekey.setAttribute("preKeyId", preKeyRecord.getId());
            prekey.setContent(
                    Base64.encodeToString(
                            preKeyRecord.getKeyPair().getPublicKey().serialize(), Base64.NO_WRAP));
        }

        return publish(AxolotlService.PEP_BUNDLES + ":" + deviceId, item, publishOptions);
    }

    public Iq publishVerification(
            byte[] signature, X509Certificate[] certificates, final int deviceId) {
        final Element item = new Element("item");
        item.setAttribute("id", "current");
        final Element verification = item.addChild("verification", AxolotlService.PEP_PREFIX);
        final Element chain = verification.addChild("chain");
        for (int i = 0; i < certificates.length; ++i) {
            try {
                Element certificate = chain.addChild("certificate");
                certificate.setContent(
                        Base64.encodeToString(certificates[i].getEncoded(), Base64.NO_WRAP));
                certificate.setAttribute("index", i);
            } catch (CertificateEncodingException e) {
                Log.d(Config.LOGTAG, "could not encode certificate");
            }
        }
        verification
                .addChild("signature")
                .setContent(Base64.encodeToString(signature, Base64.NO_WRAP));
        return publish(AxolotlService.PEP_VERIFICATION + ":" + deviceId, item);
    }

    public Iq queryMessageArchiveManagement(final MessageArchiveService.Query mam) {
        final Iq packet = new Iq(Iq.Type.SET);
        final Element query = packet.addChild("query", mam.version.namespace);
        query.setAttribute("queryid", mam.getQueryId());
        final Data data = new Data();
        data.setFormType(mam.version.namespace);
        if (mam.muc()) {
            packet.setTo(mam.getWith());
        } else if (mam.getWith() != null) {
            data.put("with", mam.getWith().toString());
        }
        final long start = mam.getStart();
        final long end = mam.getEnd();
        if (start != 0) {
            data.put("start", getTimestamp(start));
        }
        if (end != 0) {
            data.put("end", getTimestamp(end));
        }
        data.submit();
        query.addChild(data);
        Element set = query.addChild("set", "http://jabber.org/protocol/rsm");
        if (mam.getPagingOrder() == MessageArchiveService.PagingOrder.REVERSE) {
            set.addChild("before").setContent(mam.getReference());
        } else if (mam.getReference() != null) {
            set.addChild("after").setContent(mam.getReference());
        }
        set.addChild("max").setContent(String.valueOf(Config.PAGE_SIZE));
        return packet;
    }

    public Iq pushTokenToAppServer(Jid appServer, String token, String deviceId) {
        return pushTokenToAppServer(appServer, token, deviceId, null);
    }

    public Iq pushTokenToAppServer(Jid appServer, String token, String deviceId, Jid muc) {
        final Iq packet = new Iq(Iq.Type.SET);
        packet.setTo(appServer);
        final Element command = packet.addChild("command", Namespace.COMMANDS);
        command.setAttribute("node", "register-push-fcm");
        command.setAttribute("action", "execute");
        final Data data = new Data();
        data.put("token", token);
        data.put("android-id", deviceId);
        if (muc != null) {
            data.put("muc", muc.toString());
        }
        data.submit();
        command.addChild(data);
        return packet;
    }

    public Iq unregisterChannelOnAppServer(Jid appServer, String deviceId, String channel) {
        final Iq packet = new Iq(Iq.Type.SET);
        packet.setTo(appServer);
        final Element command = packet.addChild("command", Namespace.COMMANDS);
        command.setAttribute("node", "unregister-push-fcm");
        command.setAttribute("action", "execute");
        final Data data = new Data();
        data.put("channel", channel);
        data.put("android-id", deviceId);
        data.submit();
        command.addChild(data);
        return packet;
    }

    public Iq enablePush(final Jid jid, final String node, final String secret) {
        final Iq packet = new Iq(Iq.Type.SET);
        Element enable = packet.addChild("enable", Namespace.PUSH);
        enable.setAttribute("jid", jid);
        enable.setAttribute("node", node);
        if (secret != null) {
            Data data = new Data();
            data.setFormType(Namespace.PUBSUB_PUBLISH_OPTIONS);
            data.put("secret", secret);
            data.submit();
            enable.addChild(data);
        }
        return packet;
    }

    public Iq disablePush(final Jid jid, final String node) {
        Iq packet = new Iq(Iq.Type.SET);
        Element disable = packet.addChild("disable", Namespace.PUSH);
        disable.setAttribute("jid", jid);
        disable.setAttribute("node", node);
        return packet;
    }

    public Iq requestPubsubConfiguration(Jid jid, String node) {
        return pubsubConfiguration(jid, node, null);
    }

    public Iq publishPubsubConfiguration(Jid jid, String node, Data data) {
        return pubsubConfiguration(jid, node, data);
    }

    private Iq pubsubConfiguration(Jid jid, String node, Data data) {
        final Iq packet = new Iq(data == null ? Iq.Type.GET : Iq.Type.SET);
        packet.setTo(jid);
        Element pubsub = packet.addChild("pubsub", "http://jabber.org/protocol/pubsub#owner");
        Element configure = pubsub.addChild("configure").setAttribute("node", node);
        if (data != null) {
            configure.addChild(data);
        }
        return packet;
    }
}
