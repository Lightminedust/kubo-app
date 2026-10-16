package com.kubo.model.media;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * What a file is, for the purpose of showing it: a picture, a sound, a film, or none of those.
 *
 * <p>Decided on the name, because the alternative is reading the first bytes of the file, which means a round
 * trip to the server before Kubo can say whether a menu entry should even be there. A name that lies about
 * its contents fails when it is opened, and says so, which is the same thing that would have happened.
 *
 * <p>What is playable here is not what is playable in general. Kubo shows these through JavaFX, which carries
 * its own decoders and a short list of them: H.264 in MP4, AAC, MP3, uncompressed WAV and AIFF, PNG, JPEG,
 * GIF, BMP. The usual contents of a server, in other words, minus the ones that need a codec nobody is
 * allowed to redistribute. Matroska, WebM, AVI, FLAC and the rest are recognised anyway, so that Kubo can say
 * which it is and that it cannot open it, rather than offering a player that would show a black rectangle.
 */
public enum MediaKind {

    /** Something to watch. */
    VIDEO,
    /** Something to listen to. */
    AUDIO,
    /** Something to look at. */
    IMAGE;

    private static final Set<String> VIDEO_PLAYS = Set.of("mp4", "m4v", "flv");
    private static final Set<String> AUDIO_PLAYS = Set.of("mp3", "m4a", "aac", "wav", "aiff", "aif");
    private static final Set<String> IMAGE_PLAYS = Set.of("png", "jpg", "jpeg", "gif", "bmp");

    private static final Set<String> VIDEO_ONLY = Set.of("mkv", "webm", "avi", "mov", "wmv", "mpg", "mpeg",
            "ogv", "3gp", "ts", "m2ts", "vob", "rmvb", "asf");
    private static final Set<String> AUDIO_ONLY = Set.of("flac", "ogg", "oga", "opus", "wma", "m4b", "ape",
            "alac", "amr", "mid", "midi");
    private static final Set<String> IMAGE_ONLY = Set.of("webp", "avif", "heic", "heif", "tif", "tiff", "svg",
            "ico", "psd", "raw", "cr2", "nef", "arw", "dng");

    /** The kind this file can actually be shown as, or empty when Kubo has no player for it. */
    public static Optional<MediaKind> playable(String name) {
        String suffix = suffix(name);
        if (VIDEO_PLAYS.contains(suffix)) {
            return Optional.of(VIDEO);
        }
        if (AUDIO_PLAYS.contains(suffix)) {
            return Optional.of(AUDIO);
        }
        return IMAGE_PLAYS.contains(suffix) ? Optional.of(IMAGE) : Optional.empty();
    }

    /** The kind this file is, whether or not Kubo can show it. */
    public static Optional<MediaKind> of(String name) {
        Optional<MediaKind> plays = playable(name);
        if (plays.isPresent()) {
            return plays;
        }
        String suffix = suffix(name);
        if (VIDEO_ONLY.contains(suffix)) {
            return Optional.of(VIDEO);
        }
        if (AUDIO_ONLY.contains(suffix)) {
            return Optional.of(AUDIO);
        }
        return IMAGE_ONLY.contains(suffix) ? Optional.of(IMAGE) : Optional.empty();
    }

    /**
     * Why this file cannot be shown, or empty when it can.
     *
     * <p>A sentence rather than a silence. Somebody who right-clicks a .mkv and finds no PLAY has learnt
     * nothing; somebody who is told Kubo has no decoder for Matroska knows to download it.
     */
    public static Optional<String> whyNot(String name) {
        if (playable(name).isPresent()) {
            return Optional.empty();
        }
        String suffix = suffix(name);
        if (suffix.isEmpty()) {
            return Optional.of("THIS FILE HAS NO EXTENSION, SO KUBO CANNOT TELL WHAT IT IS");
        }
        return of(name).isPresent()
                ? Optional.of("KUBO HAS NO DECODER FOR ." + suffix.toUpperCase(Locale.ROOT)
                        + ". DOWNLOAD IT AND OPEN IT ON YOUR COMPUTER.")
                : Optional.of("." + suffix.toUpperCase(Locale.ROOT) + " IS NOT A PICTURE, A SOUND OR A FILM");
    }

    /** What to call a group of these in a sentence. */
    public String plural() {
        return switch (this) {
            case VIDEO -> "videos";
            case AUDIO -> "tracks";
            case IMAGE -> "pictures";
        };
    }

    /** Whether this kind runs on a clock, and so needs a play button and a position. */
    public boolean timed() {
        return this != IMAGE;
    }

    private static String suffix(String name) {
        String text = name == null ? "" : name.strip();
        int dot = text.lastIndexOf('.');
        // A dot at the start is a hidden file, not an extension: ".bashrc" has no suffix.
        return dot <= 0 || dot == text.length() - 1 ? "" : text.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
