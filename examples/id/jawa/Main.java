// SPDX-License-Identifier: GPL-3.0-or-later
package id.jawa;

import id.jawa.client.JaWaClient;
import id.jawa.client.JaWaClient.CarouselCardInput;
import id.jawa.client.JaWaClient.Listener;
import id.jawa.domain.store.FileAuthStore;
import id.jawa.feature.appstate.PatchName;
import id.jawa.feature.messaging.MessageEncoder.CtaButton;
import id.jawa.feature.messaging.MessageEncoder.ListRow;
import id.jawa.feature.messaging.MessageEncoder.ListSection;
import id.jawa.feature.messaging.MessageEncoder.QuickReplyButton;
import id.jawa.feature.messaging.MessageReceiver.Decoded;
import id.jawa.feature.pairing.QrTerminal;
import id.jawa.protocol.codec.BinaryNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Demo runner: connects to WhatsApp Web, prints QR strings on first run,
 * persists session creds, and reconnects without QR on subsequent runs.
 *
 * <p>Usage:
 * <pre>
 *   ./gradlew run -PsessionFile=/path/to/your.session
 * </pre>
 *
 * <p>QR strings printed to stdout in the form
 * {@code ref,base64(noisePub),base64(identityKey),base64(advSecret)}: paste each
 * into any QR generator (or use a lib like ZXing client-side) and scan with WhatsApp.
 */
public final class Main {
    public static void main(String[] args) throws Exception {
        Path sessionFile = Path.of(System.getProperty("jawa.session",
            args.length > 0 ? args[0] : "sessions/default.session"));

        // Derive a per-session Signal-state dir alongside the session file by stripping
        // the .session suffix and appending .signal. Override with -Djawa.signal_dir=...
        String suffix = ".session";
        String basePath = sessionFile.toString();
        String derivedSignalDir = basePath.endsWith(suffix)
            ? basePath.substring(0, basePath.length() - suffix.length()) + ".signal"
            : basePath + ".signal";
        Path signalDir = Path.of(System.getProperty("jawa.signal_dir", derivedSignalDir));

        System.out.println("Session file: " + sessionFile.toAbsolutePath());
        System.out.println("Signal dir  : " + signalDir.toAbsolutePath());

        FileAuthStore store = new FileAuthStore(sessionFile);
        JaWaClient client = new JaWaClient(store, signalDir).autoReconnect(false);

        client.listener(new Listener() {
            @Override public void onPaired(String jid, String pushName, String platform) {
                System.out.println(">>> PAIRED jid=" + jid + " biz=" + pushName + " platform=" + platform);
                System.out.println(">>> Creds persisted to " + sessionFile.toAbsolutePath());
            }
            @Override public void onQr(List<String> qrs) {
                String phone = System.getProperty("jawa.phone");
                if (phone != null && !phone.isBlank()) {
                    // Switch to pair-code instead of QR
                    client.requestPairingCode(phone, null).whenComplete((code, err) -> {
                        if (err != null) { System.err.println(">>> pair-code request failed: " + err); return; }
                        System.out.println();
                        System.out.println(">>> Pair code: " + code.substring(0, 4) + "-" + code.substring(4));
                        System.out.println(">>> Open WhatsApp -> Settings -> Linked Devices -> Link with phone number -> enter this code");
                        System.out.println();
                    });
                    return;
                }
                // No phone supplied: fall through to QR rendering
                if (qrs.isEmpty()) return;
                System.out.println();
                System.out.println(">>> Open WhatsApp -> Settings -> Linked Devices -> Link a Device, then scan:");
                System.out.println();
                System.out.print(QrTerminal.render(qrs.get(0)));
                System.out.println();
                System.out.println(">>> ref 1/" + qrs.size() + ": refs rotate every ~30 s; scan within window");
                System.out.println();
            }

            @Override public void onConnected() {
                System.out.println(">>> Connected");
                // Optional: request an app-state sync (-Djawa.appstate=regular_low|regular_high|regular|critical_block|critical_unblock_low).
                String appstate = System.getProperty("jawa.appstate");
                if (appstate != null && !appstate.isBlank()) {
                    PatchName name = PatchName.fromWire(appstate);
                    if (name == null) { System.err.println(">>> unknown collection: " + appstate); return; }
                    long fromVersion = Long.parseLong(System.getProperty("jawa.appstate_version", "-1"));
                    client.requestAppStateSync(name, fromVersion).whenComplete((muts, err) -> {
                        if (err != null) { System.err.println(">>> app-state sync failed: " + err); err.printStackTrace(); return; }
                        System.out.println(">>> App-state " + name.wire + ": " + muts.size() + " mutation(s) decoded");
                        for (int i = 0; i < Math.min(muts.size(), 10); i++) {
                            var m = muts.get(i);
                            System.out.println("    [" + i + "] op=" + m.operation()
                                + " ts=" + m.action().getValue().getTimestamp()
                                + " action=" + m.action().getValue().toString().replaceAll("\\s+", " ").trim());
                        }
                    });
                    return;
                }
                // Optional: send a static location pin (-Djawa.loc_chat / _lat / _lng / _name / _address).
                String locChat = System.getProperty("jawa.loc_chat");
                if (locChat != null && !locChat.isBlank()) {
                    double lat = Double.parseDouble(System.getProperty("jawa.loc_lat", "0"));
                    double lng = Double.parseDouble(System.getProperty("jawa.loc_lng", "0"));
                    String locName = System.getProperty("jawa.loc_name", "");
                    String locAddr = System.getProperty("jawa.loc_address", "");
                    client.sendLocation(locChat, lat, lng, locName, locAddr)
                        .whenComplete((id, err) -> {
                            if (err != null) { System.err.println(">>> location send failed: " + err); err.printStackTrace(); return; }
                            System.out.println(">>> Sent location id=" + id + " (" + lat + "," + lng + ")");
                        });
                    return;
                }
                // Optional: send a contact card (-Djawa.contact_chat / _name / _vcard).
                String contactChat = System.getProperty("jawa.contact_chat");
                if (contactChat != null && !contactChat.isBlank()) {
                    String dn = System.getProperty("jawa.contact_name", "Contact");
                    String vcard = System.getProperty("jawa.contact_vcard", "");
                    client.sendContact(contactChat, dn, vcard)
                        .whenComplete((id, err) -> {
                            if (err != null) { System.err.println(">>> contact send failed: " + err); err.printStackTrace(); return; }
                            System.out.println(">>> Sent contact id=" + id);
                        });
                    return;
                }
                // Optional: send a poll (-Djawa.poll_chat / _name / _options (pipe-separated) / _count).
                String pollChat = System.getProperty("jawa.poll_chat");
                if (pollChat != null && !pollChat.isBlank()) {
                    String pollName = System.getProperty("jawa.poll_name", "Pick one");
                    String optsCsv = System.getProperty("jawa.poll_options", "");
                    int count = Integer.parseInt(System.getProperty("jawa.poll_count", "1"));
                    List<String> opts = new ArrayList<>();
                    for (String o : optsCsv.split("\\|")) if (!o.isBlank()) opts.add(o.trim());
                    client.sendPoll(pollChat, pollName, opts, count)
                        .whenComplete((id, err) -> {
                            if (err != null) { System.err.println(">>> poll send failed: " + err); err.printStackTrace(); return; }
                            System.out.println(">>> Sent poll id=" + id + " (" + opts.size() + " option(s), select=" + count + ")");
                        });
                    return;
                }
                // Optional: send a text with @-mentions (-Djawa.mention_chat / _text / _jids).
                // _jids = comma-separated bare JIDs in matching order to @<number> in _text.
                String mentionChat = System.getProperty("jawa.mention_chat");
                if (mentionChat != null && !mentionChat.isBlank()) {
                    String text = System.getProperty("jawa.mention_text", "");
                    String jidsCsv = System.getProperty("jawa.mention_jids", "");
                    List<String> jids = new ArrayList<>();
                    for (String j : jidsCsv.split(",")) if (!j.isBlank()) jids.add(j.trim());
                    client.sendTextWithMentions(mentionChat, text, jids)
                        .whenComplete((id, err) -> {
                            if (err != null) { System.err.println(">>> mention send failed: " + err); err.printStackTrace(); return; }
                            System.out.println(">>> Sent mention id=" + id + " (" + jids.size() + " jid(s))");
                        });
                    return;
                }
                // Optional: hardcoded interactive demos for live testing native flow types
                // (-Djawa.demo_chat=<jid> -Djawa.demo=mixed_cta|carousel|single_select|send_location|address|quick_reply).
                String demoChat = System.getProperty("jawa.demo_chat");
                String demo = System.getProperty("jawa.demo");
                if (demoChat != null && !demoChat.isBlank() && demo != null) {
                    List<CtaButton> mix;
                    switch (demo) {
                        case "mixed_cta" -> {
                            mix = List.of(
                                CtaButton.url("🌐 Open repo", "https://github.com/jochris/JaWa"),
                                CtaButton.copy("📋 Copy code", "JAWA-2026"),
                                CtaButton.call("📞 Call WA", "+62895416602000"),
                                CtaButton.quickReply("✨ Quick reply", "qr_hello")
                            );
                            client.sendCtaButtons(demoChat, "Mixed CTA demo 🚀", "JaWa v0.0.3", mix)
                                .whenComplete((id, err) -> System.out.println(err != null ? ">>> err " + err : ">>> Sent mixed_cta id=" + id));
                        }
                        case "single_select" -> {
                            List<ListSection> sections = List.of(
                                new ListSection("Commands", List.of(
                                    new ListRow("ping", "Ping bot", "Cek bot hidup"),
                                    new ListRow("info", "Server info", "Show server status")
                                )),
                                new ListSection("Owner", List.of(
                                    new ListRow("exec", "Exec shell", "Owner-only")
                                ))
                            );
                            mix = List.of(
                                CtaButton.singleSelect("📋 Pick command", sections),
                                CtaButton.url("📚 Docs", "https://github.com/jochris/JaWa#readme")
                            );
                            client.sendCtaButtons(demoChat, "Single-select + URL demo", "JaWa", mix)
                                .whenComplete((id, err) -> System.out.println(err != null ? ">>> err " + err : ">>> Sent single_select id=" + id));
                        }
                        case "quick_reply" -> {
                            mix = List.of(
                                CtaButton.quickReply("✅ Confirm", "qr_yes"),
                                CtaButton.quickReply("❌ Cancel",  "qr_no")
                            );
                            client.sendCtaButtons(demoChat, "Quick-reply via interactive", null, mix)
                                .whenComplete((id, err) -> System.out.println(err != null ? ">>> err " + err : ">>> Sent quick_reply id=" + id));
                        }
                        case "carousel" -> {
                            // -Djawa.carousel_image=<path1>,<path2>,<path3> (jpegs)
                            String paths = System.getProperty("jawa.carousel_image", "");
                            if (paths.isEmpty()) { System.err.println(">>> need -Djawa.carousel_image=path1,path2,..."); return; }
                            List<CarouselCardInput> inputs = new ArrayList<>();
                            String[] arr = paths.split(",");
                            CtaButton[] btns = new CtaButton[] {
                                CtaButton.url("🌐 Visit", "https://github.com/jochris/JaWa"),
                                CtaButton.copy("📋 Copy", "JAWA-CARD"),
                                CtaButton.quickReply("⭐ Star", "qr_star")
                            };
                            for (int i = 0; i < arr.length; i++) {
                                try {
                                    byte[] bytes = Files.readAllBytes(Path.of(arr[i].trim()));
                                    inputs.add(new CarouselCardInput(
                                        "Card " + (i + 1), "Caption " + (i + 1),
                                        bytes, "image/jpeg", null,
                                        List.of(btns[i % btns.length])));
                                } catch (IOException e) { System.err.println(">>> read " + arr[i] + " failed: " + e); }
                            }
                            client.sendCarousel(demoChat, null, null, inputs)
                                .whenComplete((id, err) -> System.out.println(err != null ? ">>> err " + err : ">>> Sent carousel id=" + id));
                        }
                        default -> System.err.println(">>> unknown demo: " + demo);
                    }
                    return;
                }
                // Optional: send CTA buttons (URL / copy / call) via interactiveMessage.
                // _cta format: "url:label:value|copy:label:value|call:label:value"
                String ctaChat = System.getProperty("jawa.cta_chat");
                if (ctaChat != null && !ctaChat.isBlank()) {
                    String body = System.getProperty("jawa.cta_body", "");
                    String footer = System.getProperty("jawa.cta_footer", "");
                    String spec = System.getProperty("jawa.cta", "");
                    List<CtaButton> btns = new ArrayList<>();
                    for (String pair : spec.split("\\|")) {
                        if (pair.isBlank()) continue;
                        String[] parts = pair.split(":", 3);
                        if (parts.length != 3) continue;
                        String kind = parts[0].trim().toLowerCase();
                        String label = parts[1];
                        String value = parts[2];
                        btns.add(switch (kind) {
                            case "url"  -> CtaButton.url(label, value);
                            case "copy" -> CtaButton.copy(label, value);
                            case "call" -> CtaButton.call(label, value);
                            default     -> null;
                        });
                    }
                    btns.removeIf(Objects::isNull);
                    client.sendCtaButtons(ctaChat, body, footer.isEmpty() ? null : footer, btns)
                        .whenComplete((msgId, err) -> {
                            if (err != null) { System.err.println(">>> cta send failed: " + err); err.printStackTrace(); return; }
                            System.out.println(">>> Sent cta id=" + msgId + " (" + btns.size() + " button(s))");
                        });
                    return;
                }
                // Optional: send a quick-reply buttons message
                // (-Djawa.buttons_chat / _body / _footer / _buttons).
                // _buttons format: "id1:label1|id2:label2|id3:label3" (max 3).
                String buttonsChat = System.getProperty("jawa.buttons_chat");
                if (buttonsChat != null && !buttonsChat.isBlank()) {
                    String body = System.getProperty("jawa.buttons_body", "");
                    String footer = System.getProperty("jawa.buttons_footer", "");
                    String spec = System.getProperty("jawa.buttons", "");
                    List<QuickReplyButton> btns = new ArrayList<>();
                    for (String pair : spec.split("\\|")) {
                        if (pair.isBlank()) continue;
                        String[] parts = pair.split(":", 2);
                        if (parts.length != 2) continue;
                        btns.add(new QuickReplyButton(parts[0], parts[1]));
                    }
                    client.sendButtonsMessage(buttonsChat, body, footer.isEmpty() ? null : footer, btns)
                        .whenComplete((msgId, err) -> {
                            if (err != null) { System.err.println(">>> buttons send failed: " + err); err.printStackTrace(); return; }
                            System.out.println(">>> Sent buttons id=" + msgId + " (" + btns.size() + " buttons)");
                        });
                    return;
                }
                // Optional: send a list message (-Djawa.list_chat / _title / _body / _footer /
                // _button / _rows). _rows format: "secTitle1>id1=t1|id2=t2|;secTitle2>id3=t3"
                String listChat = System.getProperty("jawa.list_chat");
                if (listChat != null && !listChat.isBlank()) {
                    String title = System.getProperty("jawa.list_title", "");
                    String body = System.getProperty("jawa.list_body", "");
                    String footer = System.getProperty("jawa.list_footer", "");
                    String buttonText = System.getProperty("jawa.list_button", "Select");
                    String rowsSpec = System.getProperty("jawa.list_rows", "");
                    List<ListSection> sections = new ArrayList<>();
                    for (String secSpec : rowsSpec.split(";")) {
                        if (secSpec.isBlank()) continue;
                        String[] hr = secSpec.split(">", 2);
                        String secTitle = hr.length > 1 ? hr[0] : null;
                        String rowsPart = hr.length > 1 ? hr[1] : hr[0];
                        List<ListRow> rows = new ArrayList<>();
                        for (String r : rowsPart.split("\\|")) {
                            if (r.isBlank()) continue;
                            String[] kv = r.split("=", 2);
                            if (kv.length != 2) continue;
                            rows.add(new ListRow(kv[0], kv[1], null));
                        }
                        sections.add(new ListSection(secTitle, rows));
                    }
                    client.sendListMessage(listChat, title, body,
                            footer.isEmpty() ? null : footer, buttonText, sections)
                        .whenComplete((msgId, err) -> {
                            if (err != null) { System.err.println(">>> list send failed: " + err); err.printStackTrace(); return; }
                            System.out.println(">>> Sent list id=" + msgId + " (" + sections.size() + " section(s))");
                        });
                    return;
                }
                // Optional: send an image (-Djawa.image_chat / _path / _mimetype / _caption).
                String imageChat = System.getProperty("jawa.image_chat");
                if (imageChat != null && !imageChat.isBlank()) {
                    String imagePath = System.getProperty("jawa.image_path", "");
                    String mimetype = System.getProperty("jawa.image_mimetype", "image/jpeg");
                    String caption = System.getProperty("jawa.image_caption", "");
                    try {
                        byte[] imageBytes = Files.readAllBytes(Path.of(imagePath));
                        client.sendImage(imageChat, imageBytes, mimetype, caption.isEmpty() ? null : caption)
                            .whenComplete((msgId, err) -> {
                                if (err != null) { System.err.println(">>> image send failed: " + err); err.printStackTrace(); return; }
                                System.out.println(">>> Sent image id=" + msgId
                                    + " size=" + imageBytes.length + "B path=" + imagePath);
                            });
                    } catch (IOException e) {
                        System.err.println(">>> failed to read image: " + e);
                    }
                    return;
                }
                // Optional: edit a previously-sent message (-Djawa.edit_chat / _target_id / _new_text).
                String editChat = System.getProperty("jawa.edit_chat");
                if (editChat != null && !editChat.isBlank()) {
                    String editTargetId = System.getProperty("jawa.edit_target_id", "");
                    String editNewText = System.getProperty("jawa.edit_new_text", "");
                    client.sendEdit(editChat, editTargetId, editNewText).whenComplete((msgId, err) -> {
                        if (err != null) { System.err.println(">>> edit failed: " + err); err.printStackTrace(); return; }
                        System.out.println(">>> Sent edit id=" + msgId + " target=" + editTargetId
                            + " new text=\"" + editNewText + "\"");
                    });
                    return;
                }
                // Optional: revoke / delete-for-everyone (-Djawa.revoke_chat / _target_id / _target_sender / _from_me).
                String revokeChat = System.getProperty("jawa.revoke_chat");
                if (revokeChat != null && !revokeChat.isBlank()) {
                    String revokeTargetId = System.getProperty("jawa.revoke_target_id", "");
                    String revokeTargetSender = System.getProperty("jawa.revoke_target_sender"); // null OK
                    boolean revokeFromMe = "1".equals(System.getProperty("jawa.revoke_from_me", "1"));
                    client.sendRevoke(revokeChat, revokeTargetId, revokeTargetSender, revokeFromMe)
                        .whenComplete((msgId, err) -> {
                            if (err != null) { System.err.println(">>> revoke failed: " + err); err.printStackTrace(); return; }
                            System.out.println(">>> Sent revoke id=" + msgId + " target=" + revokeTargetId);
                        });
                    return;
                }
                // Optional: send a quoted reply (-Djawa.reply_chat / _text / _to_id / _to_sender / _to_text).
                String replyChat = System.getProperty("jawa.reply_chat");
                if (replyChat != null && !replyChat.isBlank()) {
                    String replyText = System.getProperty("jawa.reply_text", "");
                    String replyToId = System.getProperty("jawa.reply_to_id", "");
                    String replyToSender = System.getProperty("jawa.reply_to_sender"); // null OK
                    String replyToText = System.getProperty("jawa.reply_to_text", "");
                    client.sendReply(replyChat, replyText, replyToId, replyToSender, replyToText)
                        .whenComplete((msgId, err) -> {
                            if (err != null) { System.err.println(">>> reply failed: " + err); err.printStackTrace(); return; }
                            System.out.println(">>> Sent reply id=" + msgId + " text=\"" + replyText
                                + "\" quoting id=" + replyToId);
                        });
                    return;
                }
                // Optional: send a reaction (-Djawa.reaction_chat / _target_id / _target_sender / _emoji).
                String reactChat = System.getProperty("jawa.reaction_chat");
                if (reactChat != null && !reactChat.isBlank()) {
                    String reactTargetId = System.getProperty("jawa.reaction_target_id", "");
                    String reactTargetSender = System.getProperty("jawa.reaction_target_sender"); // null OK for DMs
                    boolean reactFromMe = "1".equals(System.getProperty("jawa.reaction_from_me", "0"));
                    String emoji = System.getProperty("jawa.reaction_emoji", "🔥");
                    client.sendReaction(reactChat, reactTargetId, reactTargetSender, reactFromMe, emoji)
                        .whenComplete((msgId, err) -> {
                            if (err != null) { System.err.println(">>> reaction failed: " + err); err.printStackTrace(); return; }
                            System.out.println(">>> Sent reaction id=" + msgId + " emoji=" + emoji
                                + " on target id=" + reactTargetId + " in chat=" + reactChat);
                        });
                    return; // skip default DM send below
                }
                // Optional: send to a specific group (-Djawa.target_group=<full-jid>).
                String targetGroup = System.getProperty("jawa.target_group");
                if (targetGroup != null && !targetGroup.isBlank()) {
                    String text = System.getProperty("jawa.text", "Hello group from JaWa "
                        + LocalTime.now());
                    client.sendGroupText(targetGroup, text).whenComplete((msgId, err) -> {
                        if (err != null) { System.err.println(">>> group send failed: " + err); err.printStackTrace(); return; }
                        System.out.println(">>> Sent group message id=" + msgId + " text=\"" + text + "\"");
                    });
                    return; // skip the default DM send below
                }
                // Optional: list joined groups (-Djawa.list_groups=1).
                if ("1".equals(System.getProperty("jawa.list_groups"))) {
                    client.queryJoinedGroups().whenComplete((groups, err) -> {
                        if (err != null) { System.err.println(">>> group list failed: " + err); return; }
                        System.out.println(">>> Joined groups (" + groups.size() + "):");
                        for (var g : groups) {
                            System.out.println("    " + g.jid()
                                + "  subject=\"" + g.subject() + "\""
                                + "  participants=" + g.participantJids().size());
                        }
                    });
                }
                // Demo: query devices for me-jid (or another jid via -Djawa.target=...)
                String target = System.getProperty("jawa.target",
                    client.creds() != null ? client.creds().meJid : null);
                if (target == null) return;
                // Strip device suffix: usync expects bare PN jid
                String userOnly = target.contains(":")
                    ? target.substring(0, target.indexOf(':')) + "@" + target.substring(target.indexOf('@') + 1)
                    : target;
                client.queryDevices(List.of(userOnly)).thenAccept(map -> {
                    System.out.println(">>> Device list for " + userOnly + ":");
                    var list = map.getOrDefault(userOnly, List.of());
                    if (list.isEmpty()) System.out.println("    (no devices returned)");
                    for (var d : list) {
                        System.out.println("    device id=" + d.id()
                            + " keyIndex=" + d.keyIndex()
                            + (d.hosted() ? " (hosted)" : ""));
                    }
                });
                // Bootstrap Signal sessions for the target's devices
                client.bootstrapSessions(userOnly).whenComplete((addresses, err) -> {
                    if (err != null) { System.err.println(">>> session bootstrap failed: " + err); err.printStackTrace(); return; }
                    System.out.println(">>> Installed " + addresses.size() + " Signal session(s):");
                    for (var a : addresses) System.out.println("    " + a);
                });
                // Send a text: to-self echo by default, override with -Djawa.text=...
                String text = System.getProperty("jawa.text", "Hello from JaWa " + LocalTime.now());
                client.sendText(userOnly, text).whenComplete((msgId, err) -> {
                    if (err != null) { System.err.println(">>> send failed: " + err); err.printStackTrace(); return; }
                    System.out.println(">>> Sent message id=" + msgId + " body=\"" + text + "\"");
                });
            }
            @Override public void onMessage(Decoded d) {
                if (d.text() != null) {
                    System.out.println(">>> MESSAGE from=" + d.senderJid()
                        + " id=" + d.msgId()
                        + " text=\"" + d.text() + "\"");
                } else {
                    System.out.println(">>> MESSAGE from=" + d.senderJid()
                        + " id=" + d.msgId()
                        + " (non-text payload encType=" + d.encType() + ")");
                }
            }
            @Override public void onStanza(BinaryNode node) {
                System.out.println("RX: " + node.tag() + " " + node.attrs());
            }
            @Override public void onError(Throwable t) {
                System.err.println(">>> ERROR: " + t);
                t.printStackTrace();
            }
        });

        client.connect();
        client.join();
    }
}