package ai.surgeone.altalm.bff.api;

import ai.surgeone.altalm.bff.alm.read.AlmAttachmentClient;

/**
 * One attachment, as the SPA sees it.
 *
 * <p>⚠️ No media type. The list endpoint does not fetch bytes, so the only type available here would
 * be one guessed from the extension — and a guessed type on a list that renders download links is a
 * label that can be wrong about a file the user is about to open. The extension is already visible
 * in the name; a second, more authoritative-looking version of the same guess adds nothing but
 * confidence.
 *
 * @param size bytes, or 0 when ALM reported none. Not an {@code Optional}: it is a display detail,
 *             and a size of 0 renders the same as an unknown one
 */
public record AttachmentDto(String id, String name, String description, long size) {

    static AttachmentDto of(AlmAttachmentClient.AlmAttachment a) {
        return new AttachmentDto(a.id(), a.name(), a.description(), a.size());
    }
}
