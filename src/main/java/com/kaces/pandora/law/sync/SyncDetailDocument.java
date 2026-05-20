package com.kaces.pandora.law.sync;

import java.util.List;

/**
 * 援??踰뺣졊 ?곸꽭 API ?묐떟?먯꽌 ??ν븷 ?쒕ぉ, 蹂몃Ц ?뱀뀡, ?먯궛??臾띠? 媛믪엯?덈떎.
 */
public record SyncDetailDocument(
	String title,
	List<SyncDetailSection> sections,
	List<SyncAsset> assets
) {
}
