package com.kaces.pandora.rag.collecting;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

class RagMinistryCollectionControllerTests {
	@Test
	void forwardsExplicitRefreshModeToCollectionService() {
		RagMinistryCollectionService service = mock(RagMinistryCollectionService.class);
		when(service.collect("ALL", false, 20, 3, true)).thenReturn(null);
		RagMinistryCollectionController controller = new RagMinistryCollectionController(service);

		controller.run("ALL", false, 20, 3, true);

		verify(service).collect("ALL", false, 20, 3, true);
	}
}
