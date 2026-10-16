package com.kaces.pandora.rag.importing;

import java.util.List;

/** Source coordinates only; these fields do not assert a semantic role or scope. */
public record SourceTableRow(String sourcePart, String tablePath, int rowIndex,
    String renderedText, List<Cell> cells, int textStart, int textEnd) {
    public SourceTableRow { cells = List.copyOf(cells); }
    public SourceTableRow(String sourcePart, String tablePath, int rowIndex,
        String renderedText, List<Cell> cells) {
        this(sourcePart, tablePath, rowIndex, renderedText, cells, -1, -1);
    }

    public record Cell(int sourceRow, int sourceColumn, int rowSpan, int columnSpan,
        String text, String headerPath, String headerText) { }
}
