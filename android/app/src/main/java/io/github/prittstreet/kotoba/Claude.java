package io.github.prittstreet.kotoba;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * One call to Claude from the Android side (the Live modes run without the page). Haiku: fast.
 * The tokens go to the page's cost estimate through LiveService. Network thread only.
 */
final class Claude {
    static final String QUICK = "claude-haiku-5-5";

    private Claude() {}

    /** A problem told to the user as is. */
    static final class Problem extends IOException {
        Problem(String message) {
            super(message);
        }
    }

    /** The text of the answer. IOException: its message is for the user. */
    static String ask(String key, String workspace, String prompt, int maxTokens) throws IOException, JSONException {
        if (key == null || key.isEmpty()) throw new Problem("Ajoute ta clé API dans les réglages.");
        JSONObject body = new JSONObject()
                .put("model", QUICK)
                .put("max_tokens", maxTokens)
                .put("output_config", new JSONObject().put("effort", "low"))
                // no thinking: on a hard phrase Haiku could spend all of max_tokens on it (no answer,
                // the phrase lost as noise), and the answer must come within a second
                .put("thinking", new JSONObject().put("type", "disabled"))
                .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", prompt)));
        HttpURLConnection c = (HttpURLConnection) new URL("https://api.anthropic.com/v1/messages").openConnection();
        try {
            c.setConnectTimeout(10000);
            c.setReadTimeout(30000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("content-type", "application/json");
            c.setRequestProperty("x-api-key", key);
            c.setRequestProperty("anthropic-version", "2023-06-01");
            if (workspace != null && !workspace.isEmpty()) c.setRequestProperty("anthropic-workspace-id", workspace);
            try (OutputStream o = c.getOutputStream()) {
                o.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            String res = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
            if (code >= 400) throw new Problem(problem(code, res));
            JSONObject j = new JSONObject(res);
            StringBuilder text = new StringBuilder();
            JSONArray content = j.optJSONArray("content");
            for (int i = 0; content != null && i < content.length(); i++) {
                JSONObject b = content.optJSONObject(i);
                if (b != null && "text".equals(b.optString("type"))) text.append(b.optString("text"));
            }
            JSONObject u = j.optJSONObject("usage");
            if (u != null) LiveService.addUsage(u.optInt("input_tokens"), u.optInt("output_tokens"));
            return text.toString().trim();
        } catch (Problem e) {
            throw e;
        } catch (IOException e) {
            throw new IOException("Pas de réseau : traduction impossible.", e);
        } finally {
            c.disconnect();
        }
    }

    private static String problem(int code, String res) {
        String msg = "";
        try {
            JSONObject e = new JSONObject(res).optJSONObject("error");
            if (e != null) msg = e.optString("message");
        } catch (JSONException ignored) {
            // no JSON: the status says enough
        }
        String m = msg.toLowerCase(Locale.ROOT);
        if (code == 401) return "Clé API refusée : vérifie-la dans les réglages.";
        if (m.contains("credit balance")) return "Plus de crédit sur le compte Claude.";
        if (m.contains("usage limit")) return "Limite de dépenses de la clé atteinte.";
        if (code == 429) return "Trop de demandes d’un coup.";
        if (code == 529 || code >= 500) return "Claude est surchargé.";
        return "Erreur " + code + (msg.isEmpty() ? "" : " : " + msg);
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = s.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }
}
