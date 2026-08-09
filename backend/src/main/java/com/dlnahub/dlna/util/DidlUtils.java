package com.dlnahub.dlna.util;

import com.dlnahub.dlna.model.BrowsableItem;

public class DidlUtils {

    private DidlUtils() {
    }

    public static String generateMetadataXml(BrowsableItem item) {
        StringBuilder sb = new StringBuilder();
        sb.append("<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" ");
        sb.append("xmlns:dc=\"http://purl.org/dc/elements/1.1/\" ");
        sb.append("xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">");
        sb.append("<item id=\"").append(escapeXml(item.getId())).append("\" ");
        sb.append("parentID=\"").append(escapeXml(item.getParentId())).append("\" ");
        sb.append("restricted=\"1\">");

        if (item.getTitle() != null && !item.getTitle().isEmpty()) {
            sb.append("<dc:title>").append(escapeXml(item.getTitle())).append("</dc:title>");
        }

        if (item.getClassType() != null && !item.getClassType().isEmpty()) {
            sb.append("<upnp:class>").append(escapeXml(item.getClassType())).append("</upnp:class>");
        }

        if (item.getArtist() != null && !item.getArtist().isEmpty()) {
            sb.append("<dc:creator>").append(escapeXml(item.getArtist())).append("</dc:creator>");
        }

        if (item.getAlbum() != null && !item.getAlbum().isEmpty()) {
            sb.append("<upnp:album>").append(escapeXml(item.getAlbum())).append("</upnp:album>");
        }

        if (item.getDate() != null && !item.getDate().isEmpty()) {
            sb.append("<dc:date>").append(escapeXml(item.getDate())).append("</dc:date>");
        }

        if (item.getDescription() != null && !item.getDescription().isEmpty()) {
            sb.append("<dc:description>").append(escapeXml(item.getDescription())).append("</dc:description>");
        }

        if (item.getResourceName() != null && !item.getResourceName().isEmpty()) {
            String resAttrs = "";
            if (item.getProtocolInfo() != null && !item.getProtocolInfo().isEmpty()) {
                resAttrs += " protocolInfo=\"" + escapeXml(item.getProtocolInfo()) + "\"";
            }
            if (item.getDuration() != null && !item.getDuration().isEmpty()) {
                resAttrs += " duration=\"" + escapeXml(item.getDuration()) + "\"";
            }
            sb.append("<res").append(resAttrs).append(">");
            sb.append(escapeXml(item.getResourceName()));
            sb.append("</res>");
        }

        sb.append("</item>");
        sb.append("</DIDL-Lite>");

        return sb.toString();
    }

    public static String generateSimpleMetadataXml(String uri, String title, String mimeType, String protocolInfo) {
        StringBuilder sb = new StringBuilder();
        sb.append("<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" ");
        sb.append("xmlns:dc=\"http://purl.org/dc/elements/1.1/\" ");
        sb.append("xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">");
        sb.append("<item id=\"item_1\" parentID=\"0\" restricted=\"1\">");

        String classType = "object.item.audioItem.musicTrack";
        if (mimeType != null) {
            if (mimeType.startsWith("video/")) {
                classType = "object.item.videoItem";
            } else if (mimeType.startsWith("image/")) {
                classType = "object.item.imageItem";
            }
        }

        sb.append("<dc:title>").append(escapeXml(title != null ? title : "Media")).append("</dc:title>");
        sb.append("<upnp:class>").append(classType).append("</upnp:class>");

        String proto = (protocolInfo != null && !protocolInfo.isEmpty()) ? protocolInfo
                : "http-get:*:" + (mimeType != null ? mimeType : "audio/mpeg") + ":*";
        sb.append("<res protocolInfo=\"").append(escapeXml(proto)).append("\">");
        sb.append(escapeXml(uri));
        sb.append("</res>");

        sb.append("</item>");
        sb.append("</DIDL-Lite>");

        return sb.toString();
    }

    private static String escapeXml(String input) {
        if (input == null) return "";
        return input.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
