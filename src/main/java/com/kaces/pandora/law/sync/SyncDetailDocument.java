package com.kaces.pandora.law.sync;

import java.util.List;

public record SyncDetailDocument(
	String title,
	List<SyncDetailSection> sections,
	List<SyncAsset> assets
) {
}
