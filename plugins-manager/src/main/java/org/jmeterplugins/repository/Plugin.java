package org.jmeterplugins.repository;


import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.http.*;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.SystemDefaultHttpClient;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.ExecutionContext;
import org.apache.http.protocol.HttpContext;
import org.apache.jmeter.engine.JMeterEngine;
import org.apache.jorphan.logging.LoggingManager;
import org.apache.log.Logger;

import java.io.*;
import java.net.URI;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Plugin {
    private static final Logger log = LoggingManager.getLoggerForClass();
    public static final String VER_STOCK = "0.0.0-STOCK";
    protected JSONObject versions = new JSONObject();
    protected String id;
    protected String markerClass;
    protected String installedPath;
    protected String installedVersion;
    protected String tempName;
    protected String destName;
    protected String name;
    protected String description;
    protected String screenshot;
    protected String helpLink;
    protected String vendor;
    protected String candidateVersion;
    protected boolean canUninstall = true;

    public Plugin(String aId) {
        id = aId;
    }

    public static Plugin fromJSON(JSONObject elm) {
        Plugin inst = new Plugin(elm.getString("id"));
        inst.markerClass = elm.getString("markerClass");
        inst.versions = elm.getJSONObject("versions");
        inst.name = elm.getString("name");
        inst.description = elm.getString("description");
        inst.screenshot = elm.getString("screenshotUrl");
        inst.helpLink = elm.getString("helpUrl");
        inst.vendor = elm.getString("vendor");
        if (elm.containsKey("canUninstall")) {
            inst.canUninstall = elm.getBoolean("canUninstall");
        }
        return inst;
    }

    @Override
    public String toString() {
        return this.id;
    }

    public void detectInstalled() {
        installedPath = getJARPath(markerClass);
        if (installedPath != null) {
            installedVersion = getVersionFromPath(installedPath);
            log.debug("Found plugin " + this + " version " + installedVersion + " at path " + installedPath);
        }

        if (isInstalled()) {
            candidateVersion = installedVersion;
        } else {
            candidateVersion = getMaxVersion();
        }
    }

    public String getMaxVersion() {
        Set<String> versions = getVersions();
        if (versions.size() > 0) {
            String[] vers = versions.toArray(new String[0]);
            return vers[vers.length - 1];
        }
        return null;
    }

    public Set<String> getVersions() {
        Set<String> versions = new TreeSet<>(new VersionComparator());
        for (Object o : this.versions.keySet()) {
            if (o instanceof String) {
                versions.add((String) o);
            }
        }

        if (isInstalled()) {
            versions.add(installedVersion);
        }
        return versions;
    }

    private String getVersionFromPath(String installedPath) {
        Pattern p = Pattern.compile("-([\\.0-9]+(-SNAPSHOT)?).jar");
        Matcher m = p.matcher(installedPath);
        if (m.find()) {
            return m.group(1);
        }
        return VER_STOCK;
    }

    private String getJARPath(String className) {
        Class c;
        try {
            c = Thread.currentThread().getContextClassLoader().loadClass(className);
        } catch (ClassNotFoundException e1) {
            log.debug("Plugin not found by class: " + className);
            return null;
        }

        String file = c.getProtectionDomain().getCodeSource().getLocation().getFile();
        if (!file.toLowerCase().endsWith(".jar")) {
            log.warn("Path is not JAR: " + file);
            return null;
        }

        return file;
    }

    public String getID() {
        return id;
    }

    public String getInstalledPath() {
        return installedPath;
    }

    public String getDestName() {
        return destName;
    }

    public String getTempName() {
        return tempName;
    }

    public boolean isInstalled() {
        return installedPath != null;
    }

    public void download() throws IOException {
        String version = getCandidateVersion();
        if (!versions.containsKey(version)) {
            throw new IllegalArgumentException("Version " + version + " not found for plugin " + this);
        }

        URI url = URI.create(versions.getJSONObject(version).getString("downloadUrl"));
        log.info("Downloading: " + url);
        HttpClient httpClient = new SystemDefaultHttpClient();
        HttpGet httpget = new HttpGet(url);

        HttpContext context = new BasicHttpContext();
        HttpResponse response = httpClient.execute(httpget, context);
        if (response.getStatusLine().getStatusCode() != HttpStatus.SC_OK)
            throw new IOException(response.getStatusLine().toString());

        HttpEntity entity = response.getEntity();

        File tempFile = File.createTempFile(id, ".jar");

        InputStream inputStream = entity.getContent();
        OutputStream outputStream = new FileOutputStream(tempFile);
        IOUtils.copy(inputStream, outputStream);
        outputStream.close();

        tempName = tempFile.getPath();

        Header cd = response.getLastHeader("Content-Disposition");
        File f = new File(JMeterEngine.class.getProtectionDomain().getCodeSource().getLocation().getFile());
        String filename;
        if (cd != null) {
            filename = cd.getValue().split(";")[1].split("=")[1];
        } else {
            HttpUriRequest currentReq = (HttpUriRequest) context.getAttribute(ExecutionContext.HTTP_REQUEST);
            HttpHost currentHost = (HttpHost) context.getAttribute(ExecutionContext.HTTP_TARGET_HOST);
            String currentUrl = (currentReq.getURI().isAbsolute()) ? currentReq.getURI().toString() : (currentHost.toURI() + currentReq.getURI());
            filename = FilenameUtils.getName(currentUrl);
        }
        destName = f.getParent() + File.separator + filename;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getScreenshot() {
        return screenshot;
    }

    public String getHelpLink() {
        return helpLink;
    }

    public String getVendor() {
        return vendor;
    }

    public String getCandidateVersion() {
        return candidateVersion;
    }

    public boolean canUninstall() {
        return canUninstall;
    }

    public String getInstalledVersion() {
        return installedVersion;
    }

    public void setCandidateVersion(String candidateVersion) {
        this.candidateVersion = candidateVersion;
    }

    public boolean isUpgradable() {
        if (!isInstalled()) {
            return false;
        }

        VersionComparator comparator = new VersionComparator();
        return comparator.compare(getInstalledVersion(), getMaxVersion()) < 0;
    }

    public Set<String> getDepends() {
        Set<String> depends = new HashSet<>();
        JSONObject version = versions.getJSONObject(getCandidateVersion());
        if (version.containsKey("depends")) {
            JSONArray list = version.getJSONArray("depends");
            for (Object o : list) {
                if (o instanceof String) {
                    String dep = (String) o;
                    Pattern p = Pattern.compile("([^=<>]+)([=<>]+[0-9.]+)?");
                    Matcher m = p.matcher(dep);
                    if (!m.find()) {
                        throw new IllegalArgumentException("Cannot parse depend str: " + dep);
                    }
                    depends.add(m.group(1));
                }
            }
        }
        return depends;
    }

    private class VersionComparator implements java.util.Comparator<String> {
        @Override
        public int compare(String a, String b) {
            String[] aParts = a.split("\\W+");
            String[] bParts = b.split("\\W+");

            for (int aN = 0; aN < aParts.length; aN++) {
                if (aN < bParts.length) {
                    int res = compare2(aParts[aN], bParts[aN]);
                    if (res != 0) return res;
                }
            }

            return a.compareTo(b);
        }

        private int compare2(String a, String b) {
            if (a.equals(b)) {
                return 0;
            }

            Object ai, bi;
            try {
                ai = Integer.parseInt(a);
            } catch (NumberFormatException e) {
                ai = a;
            }

            try {
                bi = Integer.parseInt(b);
            } catch (NumberFormatException e) {
                bi = b;
            }

            if (ai instanceof Integer && bi instanceof Integer) {
                return Integer.compare((Integer) ai, (Integer) bi);
            } else if (ai instanceof String && bi instanceof String) {
                return ((String) ai).compareTo((String) bi);
            } else if (ai instanceof String) {
                return 1;
            } else {
                return -1;
            }
        }
    }
}
