package cn.lunadeer.reColorfulMap.utils.VaultConnect;

/** The provider returned an explicit failure response; no transaction was committed. */
public final class TransactionRejectedException extends IllegalStateException {
    public TransactionRejectedException(String message) { super(message); }
}
