package com.kaces.pandora.rag.importing;

import java.util.List;

/** Source coordinates only; these fields do not assert a semantic role or scope. */
public record SourceTableRow(String sourcePart, String tablePath, int rowIndex,
    String renderedText, List<Cell> cells) {
    public SourceTableRow { cells = List.copyOf(cells); }

    public record Cell(int sourceRow, int sourceColumn, int rowSpan, int columnSpan,
        String text, String headerPath, String headerText) { }
}
