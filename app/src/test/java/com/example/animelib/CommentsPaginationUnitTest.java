package com.example.animelib;

import com.example.animelib.models.CommentsResponse;
import com.google.gson.Gson;

import org.junit.Test;

import static org.junit.Assert.*;

public class CommentsPaginationUnitTest {

    @Test
    public void testMetaPaginationCalculations() {
        String json = "{"
                + "\"meta\": {"
                + "\"page\": 2,"
                + "\"per_page\": 20,"
                + "\"total\": 95,"
                + "\"has_next_page\": true"
                + "}"
                + "}";

        Gson gson = new Gson();
        CommentsResponse response = gson.fromJson(json, CommentsResponse.class);

        assertNotNull(response);
        assertNotNull(response.getMeta());
        assertEquals(2, response.getMeta().getPage());
        assertTrue(response.getMeta().isHas_next_page());
        assertEquals(Integer.valueOf(95), response.getMeta().getTotal());
        assertEquals(Integer.valueOf(5), response.getMeta().getTotal_pages());
    }

    @Test
    public void testMetaWithoutTotal() {
        String json = "{"
                + "\"meta\": {"
                + "\"page\": 1,"
                + "\"per_page\": 20,"
                + "\"has_next_page\": false"
                + "}"
                + "}";

        Gson gson = new Gson();
        CommentsResponse response = gson.fromJson(json, CommentsResponse.class);

        assertNotNull(response);
        assertNotNull(response.getMeta());
        assertEquals(1, response.getMeta().getPage());
        assertFalse(response.getMeta().isHas_next_page());
        assertNull(response.getMeta().getTotal_pages());
    }
}
