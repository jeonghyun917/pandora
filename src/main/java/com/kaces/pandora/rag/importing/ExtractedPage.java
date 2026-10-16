package com.kaces.pandora.rag.importing;

import java.util.List;

public record ExtractedPage(
	Integer pageNo,
	String text,
	List<SourceTableRow> tableRows
) {
	public ExtractedPage { tableRows = tableRows == null ? List.of() : List.copyOf(tableRows); }
	public ExtractedPage(Integer pageNo, String text) { this(pageNo, text, List.of()); }
}
