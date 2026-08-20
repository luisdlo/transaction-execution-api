package com.spin.transactions.service;

import com.spin.transactions.model.PagedResult;
import com.spin.transactions.model.Transaction;
import com.spin.transactions.model.TransactionCommand;
import com.spin.transactions.model.TransactionFilter;

public interface TransactionService {

    Transaction execute(TransactionCommand command);

    PagedResult<Transaction> find(TransactionFilter filter, int page, int limit);
}
