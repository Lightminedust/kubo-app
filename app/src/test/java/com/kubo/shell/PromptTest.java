package com.kubo.shell;

import com.kubo.model.shell.Prompt;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guessing whether a command is asking for a secret.
 *
 * <p>A guess. SSH carries no "the remote turned echo off" message, so the only thing to look at is what the
 * program wrote. Everything here decides where a switch starts, never where it stays.
 */
class PromptTest {

    @Test
    void recognisesWhatRealProgramsActuallyPrint() {
        for (String line : new String[]{
                "[sudo] password for xana:",
                "Password:",
                "Enter passphrase for key '/home/xana/.ssh/id_ed25519':",
                "Password for 'https://xana@github.com':",
                "Enter PIN:",
                "Repeat password:",
                "Mot de passe :"}) {
            assertTrue(Prompt.asksForASecret(line), line);
        }
    }

    @Test
    void aSentenceAboutAPasswordIsNotAPrompt() {
        // Masking what somebody types next because a line mentioned the word would be wrong and baffling.
        for (String line : new String[]{
                "password authentication failed",
                "Permission denied, please try again.",
                "sudo: no password was provided",
                "Changing password for xana",
                ""}) {
            assertFalse(Prompt.asksForASecret(line), line);
        }
    }

    @Test
    void aQuestionThatIsNotASecretIsNotMasked() {
        // Typing a username behind asterisks helps nobody.
        for (String line : new String[]{
                "Username for 'https://github.com':",
                "Login:",
                "Are you sure you want to continue connecting (yes/no)?",
                "Do you want to continue? [Y/n]"}) {
            assertFalse(Prompt.asksForASecret(line), line);
        }
    }

    @Test
    void aQuestionIsWorthShowingAboveTheField() {
        assertTrue(Prompt.asksSomething("Username for 'https://github.com':"));
        assertTrue(Prompt.asksSomething("Overwrite /etc/nginx/sites-available/x? [y/N]"));
        assertTrue(Prompt.asksSomething("Password:"));
        assertFalse(Prompt.asksSomething("total 48"));
        assertFalse(Prompt.asksSomething(""));
        assertFalse(Prompt.asksSomething(null));
        assertFalse(Prompt.asksSomething("x".repeat(240) + ":"), "a wall of text is not a question");
    }

    @Test
    void whatATerminalDrawsIsTidiedBeforeItIsShown() {
        // A terminal redraws in place; those sequences belong in a terminal, not above a text field.
        assertEquals("Password:", Prompt.tidy("\u001B[0m\u001B[1mPassword:\u001B[0m"));
        assertEquals("Enter passphrase:", Prompt.tidy("\rEnter passphrase:  "));
        assertEquals("", Prompt.tidy(null));
        assertTrue(Prompt.asksForASecret(Prompt.tidy("\u001B[1;32m[sudo] password for xana:\u001B[0m")),
                "the colours a program adds must not stop it being recognised");
    }

    @Test
    void aShellPromptComesOutWithNothingLeftOfItsSequences() {
        // What zsh actually sends when it takes the line: the window title, a bell, the keypad mode. None of
        // it is meant to be seen, and all of it turned up in the console as a hostname, a box and an equals.
        String drawn = "\u001B]0;root@vmi3073638:/home/xana\u0007\u001B[01;31mroot@vmi3073638\u001B[00m:"
                + "\u001B[01;34m/home/xana\u001B[00m# \u001B=";

        assertEquals("root@vmi3073638:/home/xana#", Prompt.tidy(drawn));
    }

    @Test
    void aTitleWithNoBellAfterItStillGoes() {
        // The other ending a title can have, and a title left unterminated at the end of what arrived.
        assertEquals("ready", Prompt.tidy("\u001B]2;a title\u001B\\ready"));
        assertEquals("", Prompt.tidy("\u001B]0;a title that never ends"));
    }
}
