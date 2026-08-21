package com.kaces.pandora.lawdata.sync;

import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
class SpringLawActivationTransactionExecutor implements LawActivationTransactionExecutor {
	private final TransactionTemplate transactionTemplate;

	SpringLawActivationTransactionExecutor(PlatformTransactionManager transactionManager) {
		this.transactionTemplate = new TransactionTemplate(transactionManager);
	}

	@Override
	public <T> T inTransaction(Supplier<T> action) {
		return transactionTemplate.execute(status -> action.get());
	}
}
