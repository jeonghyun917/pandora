package com.kaces.pandora.lawdata.sync;

import java.util.function.Supplier;

interface LawActivationTransactionExecutor {
	<T> T inTransaction(Supplier<T> action);
}
