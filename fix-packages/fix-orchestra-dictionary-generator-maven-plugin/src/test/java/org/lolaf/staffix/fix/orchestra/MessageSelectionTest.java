/*
 * Copyright © 2024-2026 Lolaf.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.lolaf.staffix.fix.orchestra;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Reading the list, which is a file a person maintains by hand and so has to forgive the things people write in
 * files: comments, notes at the end of a line, blank lines, and the same message named twice.
 */
public class MessageSelectionTest {

    private static InputStream holding(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void testCommentsBlankLinesAndDuplicatesAreDropped() throws Exception {
        MessageSelection selection = MessageSelection.read(holding(String.join("\n",
                "# what this module is about",
                "",
                "NewOrderSingle   # the bread and butter",
                "  ExecutionReport  ",
                "NewOrderSingle",
                "   # indented comment",
                "")));

        assertEquals(2, selection.size());
        assertFalse(selection.isEmpty());
    }

    @Test
    public void testAFileOfNothingButCommentsIsEmpty() throws Exception {
        MessageSelection selection = MessageSelection.read(holding("# nothing here\n\n   \n"));

        assertTrue(selection.isEmpty());
        assertEquals(0, selection.size());
    }

    @Test
    public void testUnmatchedEntriesComeBackInTheOrderTheyWereWritten() throws Exception {
        MessageSelection selection = MessageSelection.of("Zebra", "NewOrderSingle", "Aardvark");

        List<String> unmatched = selection.unmatchedIn(List.of(TestElements.message("NewOrderSingle", "D")));

        assertEquals("the order of the file, so a reader can find them in it", List.of("Zebra", "Aardvark"), unmatched);
    }

    @Test
    public void testAnEntryMatchesEitherTheNameOrTheMsgType() throws Exception {
        MessageSelection selection = MessageSelection.of("D");

        assertTrue(selection.keeps(TestElements.message("NewOrderSingle", "D")));
        assertFalse(selection.keeps(TestElements.message("ExecutionReport", "8")));
        assertTrue(selection.unmatchedIn(List.of(TestElements.message("NewOrderSingle", "D"))).isEmpty());
    }
}
