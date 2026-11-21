package tr.trtmail.android;

import android.util.Base64;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * EWS (Exchange Web Services) client with NTLM authentication.
 * Mirrors the original BMail Java desktop EwsClient class.
 */
public class EwsClient {

    private static final String TAG = "EwsClient";
    private static final MediaType XML_MEDIA_TYPE = MediaType.parse("text/xml; charset=utf-8");

    public static class EwsException extends Exception {
        public final int httpCode;
        EwsException(String message) { super(message); this.httpCode = 0; }
        EwsException(String message, int code) { super(message); this.httpCode = code; }
    }

    public static class MailItem {
        public String id;
        public String changeKey;
        public String subject;
        public String from;
        public String fromEmail;
        public String date;
        public boolean isRead;
        public boolean hasAttachments;
    }

    public static class MailDetail {
        public String id;
        public String subject;
        public String from;
        public String fromEmail;
        public String to;
        public String cc;
        public String date;
        public String bodyHtml;
        public List<AttachmentInfo> attachments = new ArrayList<>();
    }

    public static class AttachmentInfo {
        public String id;
        public String name;
        public long size;
        public boolean isInline;
        public String contentId;
    }

    public static class FolderInfo {
        public String id;
        public String name;
        public int totalCount;
        public int unreadCount;
    }

    public static class Person {
        public String name;
        public String email;
        @Override public String toString() { return name + " <" + email + ">"; }
    }

    // ── NTLM state ──────────────────────────────────────────────────────────
    private final String ewsUrl;
    private final String domain;
    private final String username;
    private final String password;

    private final OkHttpClient http;
    private String ntlmType1;   // cached Base64 type-1 token

    public EwsClient(String ewsUrl, String domain, String username, String password) {
        this.ewsUrl   = ewsUrl;
        this.domain   = domain;
        this.username = username;
        this.password = password;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /** Quick connectivity/auth check — just fetches the inbox count. */
    public void testConnection() throws EwsException {
        listFolder("inbox", 1, 0, null);
    }

    public List<MailItem> listFolder(String folder, int maxItems, int offset, String query)
            throws EwsException {
        String soap = buildFindItemSoap(folder, maxItems, offset, query);
        String xml  = sendSoap(soap);
        return parseFindItemResponse(xml);
    }

    public MailDetail getMailDetail(String itemId) throws EwsException {
        String soap = buildGetItemSoap(itemId);
        String xml  = sendSoap(soap);
        return parseGetItemResponse(xml);
    }

    public byte[] getAttachment(String attachmentId) throws EwsException {
        String soap = buildGetAttachmentSoap(attachmentId);
        String xml  = sendSoap(soap);
        return parseAttachmentContent(xml);
    }

    public void deleteItem(String itemId, boolean hardDelete) throws EwsException {
        String dispType = hardDelete ? "HardDelete" : "MoveToDeletedItems";
        String soap = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\""
                + " xmlns:t=\"http://schemas.microsoft.com/exchange/services/2006/types\""
                + " xmlns:m=\"http://schemas.microsoft.com/exchange/services/2006/messages\">"
                + "<soap:Body><m:DeleteItem DeleteType=\"" + dispType + "\" SendMeetingCancellations=\"SendToNone\">"
                + "<m:ItemIds><t:ItemId Id=\"" + itemId + "\"/></m:ItemIds>"
                + "</m:DeleteItem></soap:Body></soap:Envelope>";
        sendSoap(soap);
    }

    public void sendMessage(String to, String cc, String bcc,
                            String subject, String bodyHtml,
                            List<String> attachmentNames,
                            List<byte[]> attachmentData) throws EwsException {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>");
        sb.append("<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"");
        sb.append(" xmlns:t=\"http://schemas.microsoft.com/exchange/services/2006/types\"");
        sb.append(" xmlns:m=\"http://schemas.microsoft.com/exchange/services/2006/messages\">");
        sb.append("<soap:Body><m:CreateItem MessageDisposition=\"SendAndSaveCopy\">");
        sb.append("<m:SavedItemFolderId><t:DistinguishedFolderId Id=\"sentitems\"/></m:SavedItemFolderId>");
        sb.append("<m:Items><t:Message>");
        sb.append("<t:Subject>").append(escapeXml(subject)).append("</t:Subject>");
        sb.append("<t:Body BodyType=\"HTML\">").append(escapeXml(bodyHtml)).append("</t:Body>");
        appendRecipients(sb, "ToRecipients", to);
        if (cc != null && !cc.isEmpty()) appendRecipients(sb, "CcRecipients", cc);
        if (bcc != null && !bcc.isEmpty()) appendRecipients(sb, "BccRecipients", bcc);
        if (attachmentNames != null && !attachmentNames.isEmpty()) {
            sb.append("<t:Attachments>");
            for (int i = 0; i < attachmentNames.size(); i++) {
                sb.append("<t:FileAttachment>");
                sb.append("<t:Name>").append(escapeXml(attachmentNames.get(i))).append("</t:Name>");
                sb.append("<t:Content>").append(Base64.encodeToString(attachmentData.get(i), Base64.NO_WRAP)).append("</t:Content>");
                sb.append("</t:FileAttachment>");
            }
            sb.append("</t:Attachments>");
        }
        sb.append("</t:Message></m:Items>");
        sb.append("</m:CreateItem></soap:Body></soap:Envelope>");
        sendSoap(sb.toString());
    }

    public List<Person> resolveNames(String query) throws EwsException {
        String soap = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\""
                + " xmlns:t=\"http://schemas.microsoft.com/exchange/services/2006/types\""
                + " xmlns:m=\"http://schemas.microsoft.com/exchange/services/2006/messages\">"
                + "<soap:Body><m:ResolveNames ReturnFullContactData=\"false\">"
                + "<m:UnresolvedEntry>" + escapeXml(query) + "</m:UnresolvedEntry>"
                + "</m:ResolveNames></soap:Body></soap:Envelope>";
        String xml = sendSoap(soap);
        return parseResolveNamesResponse(xml);
    }

    // ── SOAP builders ────────────────────────────────────────────────────────

    private String buildFindItemSoap(String folder, int max, int offset, String query) {
        String folderId = distinguishedFolderId(folder);
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>");
        sb.append("<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"");
        sb.append(" xmlns:t=\"http://schemas.microsoft.com/exchange/services/2006/types\"");
        sb.append(" xmlns:m=\"http://schemas.microsoft.com/exchange/services/2006/messages\">");
        sb.append("<soap:Body><m:FindItem Traversal=\"Shallow\">");
        sb.append("<m:ItemShape><t:BaseShape>IdOnly</t:BaseShape>");
        sb.append("<t:AdditionalProperties>");
        sb.append("<t:FieldURI FieldURI=\"item:Subject\"/>");
        sb.append("<t:FieldURI FieldURI=\"item:DateTimeReceived\"/>");
        sb.append("<t:FieldURI FieldURI=\"item:IsRead\"/>");
        sb.append("<t:FieldURI FieldURI=\"item:HasAttachments\"/>");
        sb.append("<t:FieldURI FieldURI=\"message:From\"/>");
        sb.append("</t:AdditionalProperties></m:ItemShape>");
        sb.append("<m:IndexedPageItemView MaxEntriesReturned=\"").append(max)
          .append("\" Offset=\"").append(offset)
          .append("\" BasePoint=\"Beginning\"/>");
        if (query != null && !query.isEmpty()) {
            sb.append("<m:Restriction><t:Contains ContainmentMode=\"Substring\" ContainmentComparison=\"IgnoreCase\">");
            sb.append("<t:FieldURI FieldURI=\"item:Subject\"/>");
            sb.append("<t:Constant Value=\"").append(escapeXml(query)).append("\"/>");
            sb.append("</t:Contains></m:Restriction>");
        }
        sb.append("<m:SortOrder><t:FieldOrder Order=\"Descending\">");
        sb.append("<t:FieldURI FieldURI=\"item:DateTimeReceived\"/>");
        sb.append("</t:FieldOrder></m:SortOrder>");
        sb.append("<m:ParentFolderIds><t:DistinguishedFolderId Id=\"").append(folderId).append("\"/></m:ParentFolderIds>");
        sb.append("</m:FindItem></soap:Body></soap:Envelope>");
        return sb.toString();
    }

    private String buildGetItemSoap(String itemId) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\""
                + " xmlns:t=\"http://schemas.microsoft.com/exchange/services/2006/types\""
                + " xmlns:m=\"http://schemas.microsoft.com/exchange/services/2006/messages\">"
                + "<soap:Body><m:GetItem>"
                + "<m:ItemShape><t:BaseShape>IdOnly</t:BaseShape>"
                + "<t:AdditionalProperties>"
                + "<t:FieldURI FieldURI=\"item:Subject\"/>"
                + "<t:FieldURI FieldURI=\"item:Body\"/>"
                + "<t:FieldURI FieldURI=\"item:DateTimeReceived\"/>"
                + "<t:FieldURI FieldURI=\"item:Attachments\"/>"
                + "<t:FieldURI FieldURI=\"item:HasAttachments\"/>"
                + "<t:FieldURI FieldURI=\"message:From\"/>"
                + "<t:FieldURI FieldURI=\"message:ToRecipients\"/>"
                + "<t:FieldURI FieldURI=\"message:CcRecipients\"/>"
                + "</t:AdditionalProperties>"
                + "<t:BodyType>HTML</t:BodyType>"
                + "</m:ItemShape>"
                + "<m:ItemIds><t:ItemId Id=\"" + itemId + "\"/></m:ItemIds>"
                + "</m:GetItem></soap:Body></soap:Envelope>";
    }

    private String buildGetAttachmentSoap(String attId) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\""
                + " xmlns:t=\"http://schemas.microsoft.com/exchange/services/2006/types\""
                + " xmlns:m=\"http://schemas.microsoft.com/exchange/services/2006/messages\">"
                + "<soap:Body><m:GetAttachment>"
                + "<m:AttachmentShape><t:IncludeMimeContent>true</t:IncludeMimeContent></m:AttachmentShape>"
                + "<m:AttachmentIds><t:AttachmentId Id=\"" + attId + "\"/></m:AttachmentIds>"
                + "</m:GetAttachment></soap:Body></soap:Envelope>";
    }

    // ── HTTP + NTLM ──────────────────────────────────────────────────────────

    private String sendSoap(String soap) throws EwsException {
        byte[] body = soap.getBytes(StandardCharsets.UTF_8);
        try {
            // Phase 1: NTLM Negotiate
            Request req1 = new Request.Builder()
                    .url(ewsUrl)
                    .post(RequestBody.create(body, XML_MEDIA_TYPE))
                    .header("Authorization", "NTLM " + buildNtlmType1())
                    .header("SOAPAction", "")
                    .build();
            Response resp1 = http.newCall(req1).execute();
            String wwwAuth = resp1.header("WWW-Authenticate", "");
            resp1.close();

            if (wwwAuth == null || !wwwAuth.startsWith("NTLM ")) {
                // Server didn't issue a type-2 challenge — might be a direct 200 or error
                return issueRequest(body, null);
            }

            // Phase 2: NTLM Authenticate
            String type2Token = wwwAuth.substring(5).trim();
            String type3Token = buildNtlmType3(type2Token);

            Request req3 = new Request.Builder()
                    .url(ewsUrl)
                    .post(RequestBody.create(body, XML_MEDIA_TYPE))
                    .header("Authorization", "NTLM " + type3Token)
                    .header("SOAPAction", "")
                    .build();
            try (Response resp3 = http.newCall(req3).execute()) {
                int code = resp3.code();
                if (code == 401) throw new EwsException("Authentication failed (HTTP 401)", 401);
                if (code == 403) throw new EwsException("Access denied (HTTP 403)", 403);
                if (code < 200 || code >= 300) throw new EwsException("HTTP " + code, code);
                ResponseBody rb = resp3.body();
                return rb != null ? rb.string() : "";
            }

        } catch (EwsException e) {
            throw e;
        } catch (javax.net.ssl.SSLException e) {
            throw new EwsException("SSL error: " + e.getMessage());
        } catch (java.net.UnknownHostException e) {
            throw new EwsException("Host not found: " + e.getMessage());
        } catch (java.net.ConnectException e) {
            throw new EwsException("Connection failed: " + e.getMessage());
        } catch (IOException e) {
            throw new EwsException("Network error: " + e.getMessage());
        }
    }

    private String issueRequest(byte[] body, String auth) throws IOException, EwsException {
        Request.Builder rb = new Request.Builder()
                .url(ewsUrl)
                .post(RequestBody.create(body, XML_MEDIA_TYPE))
                .header("SOAPAction", "");
        if (auth != null) rb.header("Authorization", auth);
        try (Response r = http.newCall(rb.build()).execute()) {
            if (r.code() == 401) throw new EwsException("Authentication failed", 401);
            ResponseBody b = r.body();
            return b != null ? b.string() : "";
        }
    }

    // ── Minimal NTLM implementation ──────────────────────────────────────────

    private String buildNtlmType1() {
        // NTLM Type 1 (Negotiate) message
        byte[] msg = new byte[]{
                'N','T','L','M','S','S','P','\0',  // signature
                1,0,0,0,                              // type 1
                (byte)0x07,(byte)0x82,0,0,           // flags
                0,0,0,0, 0,0,0,0,                    // domain (empty)
                0,0,0,0, 0,0,0,0,                    // workstation (empty)
        };
        return Base64.encodeToString(msg, Base64.NO_WRAP);
    }

    private String buildNtlmType3(String type2Base64) throws EwsException {
        try {
            byte[] type2 = Base64.decode(type2Base64, Base64.DEFAULT);
            // Extract server challenge (bytes 24-31)
            byte[] challenge = new byte[8];
            System.arraycopy(type2, 24, challenge, 0, 8);

            byte[] ntHash   = ntHash(password);
            byte[] ntResp   = ntChallengeResponse(challenge, ntHash);
            byte[] domainB  = domain.toUpperCase().getBytes(StandardCharsets.UTF_16LE);
            byte[] userB    = username.toUpperCase().getBytes(StandardCharsets.UTF_16LE);

            int domOff = 72;
            int userOff = domOff + domainB.length;
            int respOff = userOff + userB.length;
            int total   = respOff + ntResp.length;

            byte[] msg = new byte[total];
            // Signature + type
            byte[] sig = {'N','T','L','M','S','S','P','\0', 3,0,0,0};
            System.arraycopy(sig, 0, msg, 0, 12);
            // LM response (empty)
            putShort(msg, 12, 0); putShort(msg, 14, 0); putInt(msg, 16, respOff);
            // NT response
            putShort(msg, 20, (short) ntResp.length);
            putShort(msg, 22, (short) ntResp.length);
            putInt(msg,  24, respOff);
            // Domain
            putShort(msg, 28, (short) domainB.length);
            putShort(msg, 30, (short) domainB.length);
            putInt(msg,  32, domOff);
            // User
            putShort(msg, 36, (short) userB.length);
            putShort(msg, 38, (short) userB.length);
            putInt(msg,  40, userOff);
            // Workstation (empty)
            putShort(msg, 44, 0); putShort(msg, 46, 0); putInt(msg, 48, userOff + userB.length);
            // Flags
            putInt(msg, 60, 0x00008201);
            // Payloads
            System.arraycopy(domainB, 0, msg, domOff, domainB.length);
            System.arraycopy(userB,   0, msg, userOff, userB.length);
            System.arraycopy(ntResp,  0, msg, respOff, ntResp.length);

            return Base64.encodeToString(msg, Base64.NO_WRAP);
        } catch (Exception e) {
            throw new EwsException("NTLM error: " + e.getMessage());
        }
    }

    private byte[] ntHash(String password) throws Exception {
        byte[] unicode = password.getBytes(StandardCharsets.UTF_16LE);
        MessageDigest md4 = new MD4Digest();
        return md4.digest(unicode);
    }

    private byte[] ntChallengeResponse(byte[] challenge, byte[] ntHash) throws Exception {
        // DES-based LM/NTLM response (simplified NTLM v1)
        byte[] paddedHash = new byte[21];
        System.arraycopy(ntHash, 0, paddedHash, 0, 16);
        byte[] result = new byte[24];
        desEncrypt(paddedHash, 0, challenge, result, 0);
        desEncrypt(paddedHash, 7, challenge, result, 8);
        desEncrypt(paddedHash, 14, challenge, result, 16);
        return result;
    }

    private static void desEncrypt(byte[] key56, int keyOff, byte[] data, byte[] out, int outOff)
            throws Exception {
        // Expand 7-byte DES key to 8-byte (add parity bits)
        byte[] key8 = new byte[8];
        key8[0] = (byte)(key56[keyOff] >> 1);
        key8[1] = (byte)(((key56[keyOff] & 0x01) << 6) | ((key56[keyOff+1] & 0xFF) >> 2));
        key8[2] = (byte)(((key56[keyOff+1] & 0x03) << 5) | ((key56[keyOff+2] & 0xFF) >> 3));
        key8[3] = (byte)(((key56[keyOff+2] & 0x07) << 4) | ((key56[keyOff+3] & 0xFF) >> 4));
        key8[4] = (byte)(((key56[keyOff+3] & 0x0F) << 3) | ((key56[keyOff+4] & 0xFF) >> 5));
        key8[5] = (byte)(((key56[keyOff+4] & 0x1F) << 2) | ((key56[keyOff+5] & 0xFF) >> 6));
        key8[6] = (byte)(((key56[keyOff+5] & 0x3F) << 1) | ((key56[keyOff+6] & 0xFF) >> 7));
        key8[7] = (byte)(key56[keyOff+6] << 1);
        for (int i = 0; i < 8; i++) key8[i] <<= 1;

        javax.crypto.SecretKey desKey = new javax.crypto.spec.SecretKeySpec(key8, "DES");
        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("DES/ECB/NoPadding");
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, desKey);
        byte[] enc = cipher.doFinal(data);
        System.arraycopy(enc, 0, out, outOff, 8);
    }

    private static void putShort(byte[] b, int off, int v) {
        b[off] = (byte)(v & 0xFF); b[off+1] = (byte)((v >> 8) & 0xFF);
    }
    private static void putInt(byte[] b, int off, int v) {
        b[off] = (byte)(v); b[off+1] = (byte)(v>>8);
        b[off+2] = (byte)(v>>16); b[off+3] = (byte)(v>>24);
    }

    // ── XML parsers ──────────────────────────────────────────────────────────

    private List<MailItem> parseFindItemResponse(String xml) throws EwsException {
        List<MailItem> result = new ArrayList<>();
        // Simple string-based XML parsing (avoids heavy DOM dependency on Android)
        String[] msgBlocks = splitByTag(xml, "t:Message", "/t:Message");
        for (String block : msgBlocks) {
            MailItem item = new MailItem();
            item.id        = attr(block, "Id");
            item.changeKey = attr(block, "ChangeKey");
            item.subject   = tagVal(block, "t:Subject");
            item.date      = tagVal(block, "t:DateTimeReceived");
            item.isRead    = "true".equalsIgnoreCase(tagVal(block, "t:IsRead"));
            item.hasAttachments = "true".equalsIgnoreCase(tagVal(block, "t:HasAttachments"));
            String nameBlock = tagBlock(block, "t:Name");
            String emailBlock = tagBlock(block, "t:EmailAddress");
            item.from      = nameBlock != null ? nameBlock : "";
            item.fromEmail = emailBlock != null ? emailBlock : "";
            if (item.id != null) result.add(item);
        }
        return result;
    }

    private MailDetail parseGetItemResponse(String xml) throws EwsException {
        MailDetail d = new MailDetail();
        String block = firstTagBlock(xml, "t:Message");
        if (block == null) block = xml;
        d.id      = attr(block, "Id");
        d.subject = tagVal(block, "t:Subject");
        d.date    = tagVal(block, "t:DateTimeReceived");

        String fromBlock = tagBlock(block, "t:From");
        if (fromBlock != null) {
            d.from      = tagVal(fromBlock, "t:Name");
            d.fromEmail = tagVal(fromBlock, "t:EmailAddress");
        }
        d.to = collectEmails(block, "t:ToRecipients");
        d.cc = collectEmails(block, "t:CcRecipients");

        String bodyBlock = firstTagBlock(xml, "t:Body");
        d.bodyHtml = bodyBlock != null ? unescapeXml(bodyBlock) : "";

        // Attachments
        String[] attBlocks = splitByTag(xml, "t:FileAttachment", "/t:FileAttachment");
        for (String ab : attBlocks) {
            AttachmentInfo att = new AttachmentInfo();
            att.id        = attr(ab, "Id");
            att.name      = tagVal(ab, "t:Name");
            att.isInline  = "true".equalsIgnoreCase(tagVal(ab, "t:IsInline"));
            att.contentId = tagVal(ab, "t:ContentId");
            String sizeStr = tagVal(ab, "t:Size");
            if (sizeStr != null) {
                try { att.size = Long.parseLong(sizeStr); } catch (NumberFormatException ignored) {}
            }
            if (att.id != null) d.attachments.add(att);
        }
        return d;
    }

    private byte[] parseAttachmentContent(String xml) throws EwsException {
        String b64 = tagVal(xml, "t:Content");
        if (b64 == null) throw new EwsException("No attachment content in response");
        return Base64.decode(b64.trim(), Base64.DEFAULT);
    }

    private List<Person> parseResolveNamesResponse(String xml) {
        List<Person> list = new ArrayList<>();
        String[] blocks = splitByTag(xml, "t:Resolution", "/t:Resolution");
        for (String b : blocks) {
            Person p = new Person();
            p.name  = tagVal(b, "t:DisplayName");
            p.email = tagVal(b, "t:EmailAddress");
            if (p.name != null && p.email != null) list.add(p);
        }
        return list;
    }

    // ── Minimal XML helpers ──────────────────────────────────────────────────

    private String[] splitByTag(String xml, String open, String close) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        while (true) {
            int s = xml.indexOf("<" + open, start);
            if (s < 0) break;
            int e = xml.indexOf("<" + close + ">", s);
            if (e < 0) break;
            e += ("<" + close + ">").length();
            parts.add(xml.substring(s, e));
            start = e;
        }
        return parts.toArray(new String[0]);
    }

    private String firstTagBlock(String xml, String tag) {
        int s = xml.indexOf("<" + tag);
        if (s < 0) return null;
        int contentStart = xml.indexOf('>', s) + 1;
        int e = xml.indexOf("</" + tag + ">", contentStart);
        if (e < 0) return null;
        return xml.substring(contentStart, e);
    }

    private String tagBlock(String xml, String tag) {
        return firstTagBlock(xml, tag);
    }

    private String tagVal(String xml, String tag) {
        int s = xml.indexOf("<" + tag);
        if (s < 0) return null;
        int contentStart = xml.indexOf('>', s) + 1;
        int e = xml.indexOf("</" + tag + ">", contentStart);
        if (e < 0) return null;
        return xml.substring(contentStart, e).trim();
    }

    private String attr(String xml, String attrName) {
        int idx = xml.indexOf(attrName + "=\"");
        if (idx < 0) return null;
        int start = idx + attrName.length() + 2;
        int end = xml.indexOf('"', start);
        if (end < 0) return null;
        return xml.substring(start, end);
    }

    private String collectEmails(String xml, String containerTag) {
        String block = tagBlock(xml, containerTag);
        if (block == null) return "";
        StringBuilder sb = new StringBuilder();
        String[] mboxes = splitByTag(block, "t:Mailbox", "/t:Mailbox");
        for (String m : mboxes) {
            String name  = tagVal(m, "t:Name");
            String email = tagVal(m, "t:EmailAddress");
            if (sb.length() > 0) sb.append("; ");
            if (name != null && !name.isEmpty()) sb.append(name).append(" <").append(email).append(">");
            else if (email != null) sb.append(email);
        }
        return sb.toString();
    }

    private static void appendRecipients(StringBuilder sb, String tag, String recipients) {
        sb.append("<t:").append(tag).append(">");
        for (String r : recipients.split("[;,]")) {
            r = r.trim();
            if (r.isEmpty()) continue;
            String email = r.contains("<") ? r.replaceAll(".*<(.+)>.*", "$1").trim() : r;
            String name  = r.contains("<") ? r.replaceAll("<.*", "").trim() : r;
            sb.append("<t:Mailbox><t:Name>").append(escapeXml(name)).append("</t:Name>")
              .append("<t:EmailAddress>").append(escapeXml(email)).append("</t:EmailAddress>")
              .append("</t:Mailbox>");
        }
        sb.append("</t:").append(tag).append(">");
    }

    private static String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
                .replace("\"","&quot;").replace("'","&apos;");
    }

    private static String unescapeXml(String s) {
        if (s == null) return "";
        return s.replace("&amp;","&").replace("&lt;","<").replace("&gt;",">")
                .replace("&quot;","\"").replace("&apos;","'");
    }

    private static String distinguishedFolderId(String folder) {
        switch (folder.toLowerCase()) {
            case "drafts":      return "drafts";
            case "sent":
            case "sentitems":   return "sentitems";
            case "deleted":
            case "deleteditems":return "deleteditems";
            case "junk":
            case "junkemail":   return "junkemail";
            default:            return "inbox";
        }
    }

    // ── MD4 (needed for NTLM NT hash) ───────────────────────────────────────

    static class MD4Digest extends MessageDigest {
        private int A, B, C, D;
        private final byte[] xBuf = new byte[4];
        private int xBufOff;
        private long byteCount;
        private final int[] X = new int[16];
        private int xOff;

        MD4Digest() {
            super("MD4");
            reset();
        }

        @Override protected void engineUpdate(byte b) {
            xBuf[xBufOff++] = b;
            if (xBufOff == 4) { processWord(xBuf, 0); xBufOff = 0; }
            byteCount++;
        }

        @Override protected void engineUpdate(byte[] in, int inOff, int len) {
            while (xBufOff != 0 && len > 0) { engineUpdate(in[inOff++]); len--; }
            while (len > 3) { processWord(in, inOff); inOff += 4; len -= 4; byteCount += 4; }
            while (len > 0) { engineUpdate(in[inOff++]); len--; }
        }

        @Override protected byte[] engineDigest() {
            long bitLength = byteCount * 8;
            engineUpdate((byte) 128);
            while (xBufOff != 0) engineUpdate((byte) 0);
            if (xOff > 14) { processBlock(); }
            X[14] = (int)(bitLength & 0xFFFFFFFFL);
            X[15] = (int)(bitLength >>> 32);
            processBlock();
            byte[] out = new byte[16];
            unpackWord(A, out, 0); unpackWord(B, out, 4);
            unpackWord(C, out, 8); unpackWord(D, out, 12);
            engineReset();
            return out;
        }

        @Override protected void engineReset() { reset(); }

        private void reset() {
            A = 0x67452301; B = (int)0xEFCDAB89L;
            C = (int)0x98BADCFEL; D = 0x10325476;
            xBufOff = 0; byteCount = 0; xOff = 0;
            java.util.Arrays.fill(X, 0);
            java.util.Arrays.fill(xBuf, (byte)0);
        }

        private void processWord(byte[] in, int inOff) {
            X[xOff++] = (in[inOff]&0xFF) | ((in[inOff+1]&0xFF)<<8)
                      | ((in[inOff+2]&0xFF)<<16) | ((in[inOff+3]&0xFF)<<24);
            if (xOff == 16) processBlock();
        }

        private static void unpackWord(int word, byte[] out, int outOff) {
            out[outOff]   = (byte) word;       out[outOff+1] = (byte)(word>>8);
            out[outOff+2] = (byte)(word>>16);  out[outOff+3] = (byte)(word>>24);
        }

        private static int rotateLeft(int x, int n) { return (x << n) | (x >>> (32-n)); }
        private static int F(int u,int v,int w){ return (u&v)|((~u)&w); }
        private static int G(int u,int v,int w){ return (u&v)|(u&w)|(v&w); }
        private static int H(int u,int v,int w){ return u^v^w; }

        private void processBlock() {
            int a=A,b=B,c=C,d=D;
            a=rotateLeft(a+F(b,c,d)+X[0],3); d=rotateLeft(d+F(a,b,c)+X[1],7);
            c=rotateLeft(c+F(d,a,b)+X[2],11); b=rotateLeft(b+F(c,d,a)+X[3],19);
            a=rotateLeft(a+F(b,c,d)+X[4],3); d=rotateLeft(d+F(a,b,c)+X[5],7);
            c=rotateLeft(c+F(d,a,b)+X[6],11); b=rotateLeft(b+F(c,d,a)+X[7],19);
            a=rotateLeft(a+F(b,c,d)+X[8],3); d=rotateLeft(d+F(a,b,c)+X[9],7);
            c=rotateLeft(c+F(d,a,b)+X[10],11); b=rotateLeft(b+F(c,d,a)+X[11],19);
            a=rotateLeft(a+F(b,c,d)+X[12],3); d=rotateLeft(d+F(a,b,c)+X[13],7);
            c=rotateLeft(c+F(d,a,b)+X[14],11); b=rotateLeft(b+F(c,d,a)+X[15],19);
            int q=0x5A827999;
            a=rotateLeft(a+G(b,c,d)+X[0]+q,3); d=rotateLeft(d+G(a,b,c)+X[4]+q,5);
            c=rotateLeft(c+G(d,a,b)+X[8]+q,9); b=rotateLeft(b+G(c,d,a)+X[12]+q,13);
            a=rotateLeft(a+G(b,c,d)+X[1]+q,3); d=rotateLeft(d+G(a,b,c)+X[5]+q,5);
            c=rotateLeft(c+G(d,a,b)+X[9]+q,9); b=rotateLeft(b+G(c,d,a)+X[13]+q,13);
            a=rotateLeft(a+G(b,c,d)+X[2]+q,3); d=rotateLeft(d+G(a,b,c)+X[6]+q,5);
            c=rotateLeft(c+G(d,a,b)+X[10]+q,9); b=rotateLeft(b+G(c,d,a)+X[14]+q,13);
            a=rotateLeft(a+G(b,c,d)+X[3]+q,3); d=rotateLeft(d+G(a,b,c)+X[7]+q,5);
            c=rotateLeft(c+G(d,a,b)+X[11]+q,9); b=rotateLeft(b+G(c,d,a)+X[15]+q,13);
            int r=0x6ED9EBA1;
            a=rotateLeft(a+H(b,c,d)+X[0]+r,3); d=rotateLeft(d+H(a,b,c)+X[8]+r,9);
            c=rotateLeft(c+H(d,a,b)+X[4]+r,11); b=rotateLeft(b+H(c,d,a)+X[12]+r,15);
            a=rotateLeft(a+H(b,c,d)+X[2]+r,3); d=rotateLeft(d+H(a,b,c)+X[10]+r,9);
            c=rotateLeft(c+H(d,a,b)+X[6]+r,11); b=rotateLeft(b+H(c,d,a)+X[14]+r,15);
            a=rotateLeft(a+H(b,c,d)+X[1]+r,3); d=rotateLeft(d+H(a,b,c)+X[9]+r,9);
            c=rotateLeft(c+H(d,a,b)+X[5]+r,11); b=rotateLeft(b+H(c,d,a)+X[13]+r,15);
            a=rotateLeft(a+H(b,c,d)+X[3]+r,3); d=rotateLeft(d+H(a,b,c)+X[11]+r,9);
            c=rotateLeft(c+H(d,a,b)+X[7]+r,11); b=rotateLeft(b+H(c,d,a)+X[15]+r,15);
            A+=a; B+=b; C+=c; D+=d;
            xOff = 0;
            java.util.Arrays.fill(X, 0);
        }
    }
}
