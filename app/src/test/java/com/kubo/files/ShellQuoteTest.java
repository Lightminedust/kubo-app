package com.kubo.files;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShellQuoteTest {

    @Test
    void plainPathIsWrappedInSingleQuotes() {
        assertEquals("'/home/xana'", ShellQuote.quote("/home/xana"));
        assertEquals("'/home/xana/my projects'", ShellQuote.quote("/home/xana/my projects"));
    }

    @Test
    void singleQuoteIsClosedEscapedAndReopened() {
        assertEquals("'it'\\''s'", ShellQuote.quote("it's"));
        assertEquals("''\\'''\\'''", ShellQuote.quote("''"));
    }

    @Test
    void shellSyntaxStaysLiteral() {
        assertEquals("'$(rm -rf ~)'", ShellQuote.quote("$(rm -rf ~)"));
        assertEquals("'a;b && c | d `x`'", ShellQuote.quote("a;b && c | d `x`"));
    }

    @Test
    void emptyTextIsAnEmptyArgument() {
        assertEquals("''", ShellQuote.quote(""));
    }
}
