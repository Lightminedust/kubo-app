package com.kubo.media;

import com.kubo.model.media.MediaKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("What a file can be shown as")
class MediaKindTest {

    @Test
    void theOnesKuboCanActuallyShow() {
        assertEquals(Optional.of(MediaKind.VIDEO), MediaKind.playable("holiday.mp4"));
        assertEquals(Optional.of(MediaKind.AUDIO), MediaKind.playable("podcast.mp3"));
        assertEquals(Optional.of(MediaKind.AUDIO), MediaKind.playable("voice.m4a"));
        assertEquals(Optional.of(MediaKind.IMAGE), MediaKind.playable("diagram.PNG"), "case does not matter");
        assertEquals(Optional.of(MediaKind.IMAGE), MediaKind.playable("photo.jpeg"));
    }

    @Test
    void theOnesItRecognisesAndStillCannotShow() {
        // Recognised so the menu can say which it is and why not, rather than offering a player that would
        // show a black rectangle.
        assertEquals(Optional.of(MediaKind.VIDEO), MediaKind.of("film.mkv"));
        assertEquals(Optional.empty(), MediaKind.playable("film.mkv"));
        assertEquals(Optional.of(MediaKind.AUDIO), MediaKind.of("master.flac"));
        assertEquals(Optional.of(MediaKind.IMAGE), MediaKind.of("logo.svg"));
    }

    @Test
    void theReasonIsASentenceAndNotASilence() {
        assertEquals(Optional.empty(), MediaKind.whyNot("holiday.mp4"));
        assertTrue(MediaKind.whyNot("film.mkv").orElseThrow().contains("NO DECODER FOR .MKV"));
        assertTrue(MediaKind.whyNot("notes.txt").orElseThrow().contains("NOT A PICTURE"));
        assertTrue(MediaKind.whyNot("dump").orElseThrow().contains("NO EXTENSION"));
    }

    @Test
    void aHiddenFileIsNotAnExtension() {
        // ".bashrc" is a name beginning with a dot, not a file of type "bashrc".
        assertEquals(Optional.empty(), MediaKind.of(".bashrc"));
        assertTrue(MediaKind.whyNot(".bashrc").orElseThrow().contains("NO EXTENSION"));
        assertEquals(Optional.empty(), MediaKind.of("archive."), "a trailing dot names nothing");
    }

    @Test
    void aDotInTheMiddleIsNotTheExtension() {
        assertEquals(Optional.of(MediaKind.VIDEO), MediaKind.playable("2026.09.25 recording.mp4"));
        assertEquals(Optional.empty(), MediaKind.of("backup.mp4.gz"), "the last one is what it is");
    }

    @Test
    void whatToCallThemAndWhetherTheyRun() {
        assertEquals("videos", MediaKind.VIDEO.plural());
        assertEquals("pictures", MediaKind.IMAGE.plural());
        assertTrue(MediaKind.VIDEO.timed());
        assertTrue(MediaKind.AUDIO.timed());
        assertEquals(false, MediaKind.IMAGE.timed(), "a picture has no position to seek to");
    }
}
