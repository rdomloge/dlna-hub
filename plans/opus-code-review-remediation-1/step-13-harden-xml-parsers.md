# Step 13 — Harden the remaining XML parsers against XXE

**Phase:** 2 — Security and robustness
**Severity:** High (report: H4)
**Files:** `backend/src/main/java/com/dlnahub/service/ContentBrowseService.java`
**Depends on:** —

## Problem

`DidlUtils.extractTitleFromMetadata` already builds its `DocumentBuilderFactory` safely —
DTDs disabled, external entities off, no XInclude. Two other parsers in
`ContentBrowseService` do not:

- `parseBrowseResult` (around line 926) — parses DIDL-Lite from any LAN media server.
- `parseSortCaps` (around line 456) — parses a raw SOAP response from the same.

Both use a default `DocumentBuilderFactory`, which resolves DTDs and external entities. A
hostile or compromised device on the network can serve XML that makes the backend read
local files or make outbound requests. The inconsistency is the tell: the hardening was
applied once and forgotten twice.

## Change

### 1. Add a shared factory helper to `ContentBrowseService`

Place it next to the other private helpers at the bottom of the class:

```java
    /**
     * A DocumentBuilderFactory with DTDs and external entities disabled. DIDL-Lite and SOAP
     * responses come from arbitrary devices on the local network, so they are untrusted input.
     * Mirrors the hardening already applied in DidlUtils.extractTitleFromMetadata.
     */
    private static DocumentBuilderFactory secureDocumentBuilderFactory(boolean namespaceAware)
            throws javax.xml.parsers.ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(namespaceAware);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory;
    }
```

### 2. Use it in `parseBrowseResult`

Replace:

```java
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            DocumentBuilder builder = factory.newDocumentBuilder();
```

with:

```java
            DocumentBuilder builder = secureDocumentBuilderFactory(true).newDocumentBuilder();
```

### 3. Use it in `parseSortCaps`

Replace:

```java
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
```

with:

```java
            DocumentBuilder builder = secureDocumentBuilderFactory(false).newDocumentBuilder();
```

### 4. Harden the XPath factory in `parseBrowseResult`

Immediately after `XPathFactory xpf = XPathFactory.newInstance();` (the existing line
reads `XPath xpath = XPathFactory.newInstance().newXPath();`), split it and set secure
processing:

```java
            XPathFactory xpathFactory = XPathFactory.newInstance();
            xpathFactory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
            XPath xpath = xpathFactory.newXPath();
```

`setFeature` throws `XPathFactoryConfigurationException`, which the surrounding
`catch (Exception e)` already covers.

## Do not

- Do not change `DidlUtils` — it is already correct and is the reference implementation.
- Do not change `preprocessMalformedXml`; the empty-namespace scrubbing is a separate,
  deliberate workaround for malformed server output.
- Do not swallow more exceptions than the existing handlers already do.

## Verify

```bash
cd backend && mvn test
```

Confirm the hardening actually took effect by feeding a DTD through the parser — add this
temporary check (then delete it) or simply confirm the app still browses the real NAS
correctly, since well-formed DIDL-Lite never contains a DOCTYPE:

```bash
grep -c "disallow-doctype-decl" backend/src/main/java/com/dlnahub/service/ContentBrowseService.java
# expect 1 (the shared helper)
grep -c "DocumentBuilderFactory.newInstance()" backend/src/main/java/com/dlnahub/service/ContentBrowseService.java
# expect 1 (only inside the helper)
```
