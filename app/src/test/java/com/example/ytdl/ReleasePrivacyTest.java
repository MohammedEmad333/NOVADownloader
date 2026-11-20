package com.example.ytdl;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.*;

/** Release guardrails for app-private data, exported components and CI signing. */
public final class ReleasePrivacyTest {
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";

    private File source(String path) {
        File file = new File(path);
        if (!file.isFile()) file = new File("app", path);
        if (!file.isFile() && path.startsWith("../")) {
            file = new File(path.substring(3));
        }
        assertTrue("Missing release source: " + path, file.isFile());
        return file;
    }

    private Document xml(String path) throws Exception {
        final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(source(path));
    }

    @Test public void appDisablesBackupAndCleartextAndProtectsComponents() throws Exception {
        final Document manifest = xml("src/main/AndroidManifest.xml");
        final Element app = (Element) manifest.getElementsByTagName("application").item(0);
        assertEquals("false", app.getAttributeNS(ANDROID, "allowBackup"));
        assertEquals("false", app.getAttributeNS(ANDROID, "usesCleartextTraffic"));
        assertEquals("@xml/backup_rules", app.getAttributeNS(ANDROID, "fullBackupContent"));
        assertEquals("@xml/data_extraction_rules",
                app.getAttributeNS(ANDROID, "dataExtractionRules"));

        final NodeList activities = manifest.getElementsByTagName("activity");
        int exportedActivities = 0;
        for (int i = 0; i < activities.getLength(); i++) {
            final Element activity = (Element) activities.item(i);
            final String name = activity.getAttributeNS(ANDROID, "name");
            if ("true".equals(activity.getAttributeNS(ANDROID, "exported"))) {
                exportedActivities++;
                assertTrue("Unexpected exported Activity: " + name,
                        ".MainActivity".equals(name) || ".LinkRouterActivity".equals(name));
            } else {
                assertEquals("false", activity.getAttributeNS(ANDROID, "exported"));
            }
        }
        assertEquals(2, exportedActivities);
        final NodeList services = manifest.getElementsByTagName("service");
        for (int i = 0; i < services.getLength(); i++) {
            assertEquals("false", ((Element) services.item(i)).getAttributeNS(
                    ANDROID, "exported"));
        }
        final NodeList permissions = manifest.getElementsByTagName("uses-permission");
        for (int i = 0; i < permissions.getLength(); i++) {
            final String name = ((Element) permissions.item(i))
                    .getAttributeNS(ANDROID, "name");
            assertFalse("Storage or broad package access unexpectedly added: " + name,
                    name.contains("EXTERNAL_STORAGE") || name.contains("MANAGE_EXTERNAL_STORAGE")
                            || name.contains("READ_MEDIA_")
                            || name.contains("QUERY_ALL_PACKAGES"));
        }
    }

    private void excludesRoot(Document xml, String nodeName) {
        final NodeList nodes = xml.getElementsByTagName(nodeName);
        assertEquals("Expected exactly one " + nodeName, 1, nodes.getLength());
        final Element section = (Element) nodes.item(0);
        final NodeList rules = section.getElementsByTagName("exclude");
        boolean coversRoot = false;
        for (int i = 0; i < rules.getLength(); i++) {
            final Element entry = (Element) rules.item(i);
            if ("root".equals(entry.getAttribute("domain"))
                    && ".".equals(entry.getAttribute("path"))) coversRoot = true;
        }
        assertTrue(nodeName + " must exclude device-specific app data", coversRoot);
    }

    @Test public void backupPoliciesExcludePrivateFilesAndPreferences() throws Exception {
        excludesRoot(xml("src/main/res/xml/backup_rules.xml"), "full-backup-content");
        final Document extraction = xml("src/main/res/xml/data_extraction_rules.xml");
        excludesRoot(extraction, "cloud-backup");
        excludesRoot(extraction, "device-transfer");
    }

    @Test public void signingIsMainOnlyAndProducesChecksummedDraftRelease() throws Exception {
        final String signed = new String(Files.readAllBytes(
                source("../.github/workflows/signed-release.yml").toPath()),
                StandardCharsets.UTF_8);
        assertTrue(signed.contains("workflow_dispatch:"));
        assertTrue(signed.contains("workflow_run:"));
        assertTrue(signed.contains("branches: [main]"));
        assertTrue(signed.contains("github.event_name == 'workflow_dispatch'"));
        assertTrue(signed.contains("github.ref == 'refs/heads/main'"));
        assertTrue(signed.contains("github.event.workflow_run.conclusion == 'success'"));
        assertTrue(signed.contains("github.event.workflow_run.head_branch == 'main'"));
        assertTrue(signed.contains("github.event.workflow_run.head_sha || github.sha"));
        assertTrue(signed.contains("permissions:\\n  contents: write"));
        assertTrue(signed.contains("zipalign"));
        assertTrue(signed.contains("apksigner"));
        assertTrue(signed.contains("sha256sum nova-release-signed.apk"));
        assertTrue(signed.contains("NOVA_VERSION_CODE"));
        assertTrue(signed.contains("NOVA_VERSION_NAME"));
        assertTrue(signed.contains("draft: true"));
        assertTrue(signed.contains("tag_name: v${{ steps.identity.outputs.version_name }}"));
        assertTrue(signed.contains("nova-release-signed.apk"));
        assertTrue(signed.contains("SIGNING_CERT_SHA256.txt"));
        assertTrue(signed.contains("Signer #1 certificate SHA-256 digest:"));
        for (String key : new String[]{"NOVA_KEYSTORE_BASE64",
                "NOVA_KEYSTORE_PASSWORD", "NOVA_KEY_ALIAS", "NOVA_KEY_PASSWORD"}) {
            assertTrue("Signing secret is not referenced: " + key, signed.contains(key));
        }
        final String regular = new String(Files.readAllBytes(
                source("../.github/workflows/build.yml").toPath()),
                StandardCharsets.UTF_8);
        assertTrue(regular.contains("assembleRelease"));
        assertTrue(regular.contains("app-release-unsigned.apk"));
        assertTrue(regular.contains("SHA256SUMS.txt"));
    }
}
