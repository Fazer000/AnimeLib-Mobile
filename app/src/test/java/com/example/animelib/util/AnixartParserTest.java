package com.example.animelib.util;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class AnixartParserTest {

    @Test
    public void testParseSampleLines() {
        String input = "1 / \"100 девушек, которые очень-очень-очень-очень-очень сильно тебя любят\" / \"Kimi no koto ga daidaidaidaidai suki na 100-nin no Kanojo\"\n"
                + "2 / \"7 семян\" / \"7 Seeds\"\n"
                + "3 / \"Клинок, рассекающий демонов\" / \"Kimetsu no Yaiba\"";

        List<AnixartParser.AnixartEntry> entries = AnixartParser.parse(input);
        assertEquals(3, entries.size());

        assertEquals(1, entries.get(0).getIndex());
        assertEquals("100 девушек, которые очень-очень-очень-очень-очень сильно тебя любят", entries.get(0).getPrimaryTitle());
        assertEquals("Kimi no koto ga daidaidaidaidai suki na 100-nin no Kanojo", entries.get(0).getSearchTitle());
        assertEquals("Kimi no koto ga daidaidaidaidai suki na 100-nin no Kanojo", entries.get(0).getEffectiveTitle());

        assertEquals(2, entries.get(1).getIndex());
        assertEquals("7 семян", entries.get(1).getPrimaryTitle());
        assertEquals("7 Seeds", entries.get(1).getSearchTitle());

        assertEquals(3, entries.get(2).getIndex());
        assertEquals("Kimetsu no Yaiba", entries.get(2).getSearchTitle());
    }

    @Test
    public void testParseUnquotedAndSpaces() {
        String input = "1 / Магическая битва / Jujutsu Kaisen\n"
                + "2/Атака титанов/Shingeki no Kyojin\n"
                + "3 / «Человек-бензопила» / «Chainsaw Man»";

        List<AnixartParser.AnixartEntry> entries = AnixartParser.parse(input);
        assertEquals(3, entries.size());

        assertEquals("Jujutsu Kaisen", entries.get(0).getSearchTitle());
        assertEquals("Shingeki no Kyojin", entries.get(1).getSearchTitle());
        assertEquals("Chainsaw Man", entries.get(2).getSearchTitle());
    }

    @Test
    public void testParseEmptyAndComments() {
        String input = "# Список просмотренного\n\n   \n1 / \"Title\" / \"Title Eng\"\n// comment";
        List<AnixartParser.AnixartEntry> entries = AnixartParser.parse(input);
        assertEquals(1, entries.size());
        assertEquals("Title Eng", entries.get(0).getSearchTitle());
    }
}
