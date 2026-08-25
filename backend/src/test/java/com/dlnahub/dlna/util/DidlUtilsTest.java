package com.dlnahub.dlna.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DidlUtilsTest {

    @Test
    void extractTitleFromMetadata_namespacedTitle_returnsDecodedTitle() {
        // given
        String metadata = """
                <DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"
                           xmlns:meta="http://purl.org/dc/elements/1.1/">
                  <item><meta:title lang="en">Tom &amp; Jerry</meta:title></item>
                </DIDL-Lite>
                """;

        // when
        String title = DidlUtils.extractTitleFromMetadata(metadata);

        // then
        assertEquals("Tom & Jerry", title);
    }

    @Test
    void extractTitleFromMetadata_malformedXml_returnsNull() {
        // given
        String metadata = "<not-closed>";

        // when
        String title = DidlUtils.extractTitleFromMetadata(metadata);

        // then
        assertNull(title);
    }
}
