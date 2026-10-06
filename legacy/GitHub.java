package com.deltapatch.launcher;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Tiny GitHub REST client: upload files to a release, start the cloud-patch workflow, download the result. */
final class GitHub {
    static final String API = "https://api.github.com";
    static final String WORKFLOW = "cloud-patch.yml";

    final String token, owner, repo;

    GitHub(String token, String ownerRepo) throws IOException {
        this.token = token == null ? "" : token.trim();
        String[] p = ownerRepo == null ? new String[0] : ownerRepo.trim().replace("https://github.com/", "").split("/");
        if (p.length < 2 || p[0].isEmpty() || p[1].isEmpty()) throw new IOException("Repo must look like owner/name");
        this.owner = p[0];
        this.repo = p[1].replace(".git", "");
    }

    static final class Resp {
        int code;
        String body = "";
        String location;
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toString("UTF-8");
    }

    private Resp call(String method, String url, String body, String accept, boolean auth) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(false);
        c.setRequestMethod(method);
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        c.setRequestProperty("Accept", accept == null ? "application/vnd.github+json" : accept);
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        c.setRequestProperty("User-Agent", "deltapatch-launcher");
        if (auth && !token.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + token);
        if (body != null) {
            byte[] b = body.getBytes("UTF-8");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setFixedLengthStreamingMode(b.length);
            try (OutputStream os = c.getOutputStream()) { os.write(b); }
        }
        Resp r = new Resp();
        r.code = c.getResponseCode();
        InputStream in = r.code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in != null) { try { r.body = readAll(in); } finally { in.close(); } }
        r.location = c.getHeaderField("Location");
        c.disconnect();
        return r;
    }

    private static IOException fail(String what, Resp r) {
        String msg = r.body;
        try { msg = new JSONObject(r.body).optString("message", r.body); } catch (JSONException ignored) { }
        if (msg.length() > 300) msg = msg.substring(0, 300);
        return new IOException(what + " failed (HTTP " + r.code + "): " + msg);
    }

    private String repoUrl() { return API + "/repos/" + owner + "/" + repo; }

    /** true = repo can be read without a token, i.e. it is PUBLIC. */
    boolean isPublic() throws IOException {
        Resp r = call("GET", repoUrl(), null, null, false);
        return r.code == 200;
    }

    /** Verifies the token can use the repo; returns the default branch. */
    String checkAccess() throws IOException, JSONException {
        Resp r = call("GET", repoUrl(), null, null, true);
        if (r.code != 200) throw fail("Repo access", r);
        JSONObject j = new JSONObject(r.body);
        JSONObject perm = j.optJSONObject("permissions");
        if (perm != null && !perm.optBoolean("push", false))
            throw new IOException("This token can read the repo but not write to it (needs Contents: write and Actions: write).");
        return j.optString("default_branch", "main");
    }

    JSONObject release(String tag) throws IOException, JSONException {
        Resp r = call("GET", repoUrl() + "/releases/tags/" + URLEncoder.encode(tag, "UTF-8"), null, null, true);
        if (r.code == 200) return new JSONObject(r.body);
        if (r.code != 404) throw fail("Release lookup", r);
        return null;
    }

    JSONObject ensureRelease(String tag) throws IOException, JSONException {
        JSONObject rel = release(tag);
        if (rel != null) return rel;
        JSONObject b = new JSONObject();
        b.put("tag_name", tag);
        b.put("name", "DeltaPatch job files");
        b.put("body", "Temporary files for the DeltaPatch cloud job. Keep this repository PRIVATE.");
        b.put("prerelease", true);
        Resp r = call("POST", repoUrl() + "/releases", b.toString(), null, true);
        if (r.code != 201) throw fail("Creating release", r);
        return new JSONObject(r.body);
    }

    void uploadAsset(JSONObject rel, String name, File f, Vcdiff.Progress prog) throws IOException, JSONException {
        JSONArray assets = rel.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                if (name.equals(a.optString("name"))) {
                    Resp d = call("DELETE", repoUrl() + "/releases/assets/" + a.getLong("id"), null, null, true);
                    if (d.code != 204) throw fail("Removing old " + name, d);
                }
            }
        }
        String url = "https://uploads.github.com/repos/" + owner + "/" + repo + "/releases/" + rel.getLong("id")
                + "/assets?name=" + URLEncoder.encode(name, "UTF-8");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(20000);
        c.setReadTimeout(10 * 60 * 1000);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("User-Agent", "deltapatch-launcher");
        c.setRequestProperty("Authorization", "Bearer " + token);
        c.setRequestProperty("Content-Type", "application/octet-stream");
        long len = f.length();
        c.setDoOutput(true);
        c.setFixedLengthStreamingMode(len);
        try (OutputStream os = c.getOutputStream(); InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[1 << 16];
            long done = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
                done += n;
                if (prog != null) prog.onProgress(done, len);
            }
            os.flush();
        }
        int code = c.getResponseCode();
        InputStream rin = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String text = rin == null ? "" : readAll(rin);
        c.disconnect();
        if (code != 201) {
            Resp r = new Resp();
            r.code = code;
            r.body = text;
            throw fail("Uploading " + name, r);
        }
    }

    void dispatch(String ref, JSONObject inputs) throws IOException, JSONException {
        JSONObject b = new JSONObject();
        b.put("ref", ref);
        b.put("inputs", inputs);
        Resp r = call("POST", repoUrl() + "/actions/workflows/" + WORKFLOW + "/dispatches", b.toString(), null, true);
        if (r.code != 204)
            throw fail("Starting the workflow (is .github/workflows/" + WORKFLOW + " in the repo's default branch?)", r);
    }

    /** Newest workflow_dispatch run of the cloud-patch workflow, or null. */
    JSONObject latestRun() throws IOException, JSONException {
        Resp r = call("GET", repoUrl() + "/actions/workflows/" + WORKFLOW + "/runs?event=workflow_dispatch&per_page=1",
                null, null, true);
        if (r.code != 200) throw fail("Reading workflow runs", r);
        JSONArray a = new JSONObject(r.body).optJSONArray("workflow_runs");
        return (a == null || a.length() == 0) ? null : a.getJSONObject(0);
    }

    /** Downloads a release asset (handles the redirect to storage without forwarding the token). */
    void download(JSONObject asset, File out, Vcdiff.Progress prog) throws IOException, JSONException {
        long total = asset.optLong("size", 0);
        Resp r = call("GET", repoUrl() + "/releases/assets/" + asset.getLong("id"), null, "application/octet-stream", true);
        String url;
        if (r.code >= 300 && r.code < 400 && r.location != null) url = r.location;
        else throw fail("Download " + asset.optString("name"), r);
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(120000);
        c.setRequestProperty("User-Agent", "deltapatch-launcher");
        int code = c.getResponseCode();
        if (code != 200) throw new IOException("Download failed (HTTP " + code + ")");
        File parent = out.getParentFile();
        if (parent != null) parent.mkdirs();
        try (InputStream in = c.getInputStream(); OutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[1 << 16];
            long done = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
                done += n;
                if (prog != null) prog.onProgress(done, total);
            }
            os.flush();
        } finally {
            c.disconnect();
        }
    }
}
