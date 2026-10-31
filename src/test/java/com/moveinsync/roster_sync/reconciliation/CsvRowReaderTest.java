package com.moveinsync.roster_sync.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CsvRowReaderTest {

    @Test
    void handlesQuotedCommasAndEscapedQuotes() {
        assertThat(CsvRowReader.parseLine("E1,3,\"Rao, Asha\",\"He said \"\"hi\"\"\",,BLR01"))
                .containsExactly("E1", "3", "Rao, Asha", "He said \"hi\"", "", "BLR01");
    }
}