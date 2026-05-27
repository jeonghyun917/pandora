package com.kaces.pandora.lawdata.sync;

import java.util.List;


public record SyncDetailDocument(
	String title,
	List<SyncDetailSection> sections,
	List<SyncAsset> assets
) {
}
