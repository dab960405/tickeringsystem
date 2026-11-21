package com.portfolio.ticketing.operation.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Operational limits for provider event processing (ADR-018/ADR-019, F4-01). Every value has an
 * explicit default and is validated at startup so an unsafe configuration fails fast.
 */
@Validated
@ConfigurationProperties(prefix = "ticketing.operation")
public class OperationProperties {

  static final Duration MAX_TX_APPLY_TIMEOUT = Duration.ofSeconds(5);
  static final Duration MAX_TX_TIMEOUT = Duration.ofSeconds(30);
  static final Duration MAX_LEASE = Duration.ofMinutes(10);

  @NotNull private Duration txRxTimeout = Duration.ofSeconds(3);
  @NotNull private Duration txClaimTimeout = Duration.ofSeconds(3);
  @NotNull private Duration txApplyTimeout = Duration.ofSeconds(5);
  @NotNull private Duration txProjectTimeout = Duration.ofSeconds(3);
  @NotNull private Duration lockTimeout = Duration.ofSeconds(1);
  @NotNull private Duration leaseDuration = Duration.ofSeconds(30);

  @Min(1)
  @Max(100)
  private int batchSize = 50;

  @Min(1)
  @Max(10)
  private int maxAttempts = 3;

  @NotNull private Duration retryBackoff = Duration.ofSeconds(5);
  @NotNull private Duration recoveryInterval = Duration.ofSeconds(1);
  private boolean recoveryEnabled = true;

  @Valid @NotNull private Provider provider = new Provider();

  @AssertTrue(message = "transaction and lock timeouts must be positive whole seconds")
  public boolean isTimeoutGranularityValid() {
    return wholePositiveSeconds(txRxTimeout)
        && wholePositiveSeconds(txClaimTimeout)
        && wholePositiveSeconds(txApplyTimeout)
        && wholePositiveSeconds(txProjectTimeout)
        && isPositive(lockTimeout);
  }

  @AssertTrue(message = "tx-apply-timeout must not exceed 5s (F4-01)")
  public boolean isTxApplyTimeoutWithinLimit() {
    return txApplyTimeout == null || txApplyTimeout.compareTo(MAX_TX_APPLY_TIMEOUT) <= 0;
  }

  @AssertTrue(message = "tx-rx, tx-claim and tx-project timeouts must not exceed 30s")
  public boolean isAuxiliaryTimeoutWithinLimit() {
    return notAbove(txRxTimeout, MAX_TX_TIMEOUT)
        && notAbove(txClaimTimeout, MAX_TX_TIMEOUT)
        && notAbove(txProjectTimeout, MAX_TX_TIMEOUT);
  }

  @AssertTrue(message = "lock-timeout must be shorter than every transaction timeout")
  public boolean isLockTimeoutShorterThanTransactions() {
    if (lockTimeout == null) {
      return true;
    }
    return shorter(lockTimeout, txRxTimeout)
        && shorter(lockTimeout, txClaimTimeout)
        && shorter(lockTimeout, txApplyTimeout)
        && shorter(lockTimeout, txProjectTimeout);
  }

  @AssertTrue(message = "lease-duration must exceed tx-apply-timeout and not exceed 10m")
  public boolean isLeaseDurationValid() {
    if (leaseDuration == null || txApplyTimeout == null) {
      return true;
    }
    return leaseDuration.compareTo(txApplyTimeout) > 0 && leaseDuration.compareTo(MAX_LEASE) <= 0;
  }

  @AssertTrue(message = "retry-backoff must be between 0s and 10m")
  public boolean isRetryBackoffValid() {
    return retryBackoff == null
        || (!retryBackoff.isNegative() && retryBackoff.compareTo(MAX_LEASE) <= 0);
  }

  @AssertTrue(message = "recovery-interval must be between 100ms and 10m")
  public boolean isRecoveryIntervalValid() {
    return recoveryInterval == null
        || (recoveryInterval.compareTo(Duration.ofMillis(100)) >= 0
            && recoveryInterval.compareTo(MAX_LEASE) <= 0);
  }

  public Duration statementTimeout(Duration transactionTimeout) {
    Duration candidate = transactionTimeout.minusSeconds(1);
    return candidate.compareTo(lockTimeout) > 0 ? candidate : lockTimeout;
  }

  private static boolean wholePositiveSeconds(Duration duration) {
    return duration == null || (isPositive(duration) && duration.toMillis() % 1000 == 0);
  }

  private static boolean isPositive(Duration duration) {
    return duration == null || (!duration.isNegative() && !duration.isZero());
  }

  private static boolean notAbove(Duration duration, Duration max) {
    return duration == null || duration.compareTo(max) <= 0;
  }

  private static boolean shorter(Duration candidate, Duration reference) {
    return reference == null || candidate.compareTo(reference) < 0;
  }

  public static class Provider {

    @NotBlank
    @Size(max = 40)
    @Pattern(regexp = "[A-Z][A-Z0-9_]*", message = "provider code must be upper snake case")
    private String code = "SIMULATED";

    @NotBlank
    @Size(max = 100)
    private String merchantAccountId = "default";

    @NotBlank
    @Size(max = 60)
    @Pattern(regexp = "[a-z][a-z0-9._-]*", message = "namespace must be lower case")
    private String identityNamespace = "payments.v1";

    public String getCode() {
      return code;
    }

    public void setCode(String code) {
      this.code = code;
    }

    public String getMerchantAccountId() {
      return merchantAccountId;
    }

    public void setMerchantAccountId(String merchantAccountId) {
      this.merchantAccountId = merchantAccountId;
    }

    public String getIdentityNamespace() {
      return identityNamespace;
    }

    public void setIdentityNamespace(String identityNamespace) {
      this.identityNamespace = identityNamespace;
    }
  }

  public Duration getTxRxTimeout() {
    return txRxTimeout;
  }

  public void setTxRxTimeout(Duration txRxTimeout) {
    this.txRxTimeout = txRxTimeout;
  }

  public Duration getTxClaimTimeout() {
    return txClaimTimeout;
  }

  public void setTxClaimTimeout(Duration txClaimTimeout) {
    this.txClaimTimeout = txClaimTimeout;
  }

  public Duration getTxApplyTimeout() {
    return txApplyTimeout;
  }

  public void setTxApplyTimeout(Duration txApplyTimeout) {
    this.txApplyTimeout = txApplyTimeout;
  }

  public Duration getTxProjectTimeout() {
    return txProjectTimeout;
  }

  public void setTxProjectTimeout(Duration txProjectTimeout) {
    this.txProjectTimeout = txProjectTimeout;
  }

  public Duration getLockTimeout() {
    return lockTimeout;
  }

  public void setLockTimeout(Duration lockTimeout) {
    this.lockTimeout = lockTimeout;
  }

  public Duration getLeaseDuration() {
    return leaseDuration;
  }

  public void setLeaseDuration(Duration leaseDuration) {
    this.leaseDuration = leaseDuration;
  }

  public int getBatchSize() {
    return batchSize;
  }

  public void setBatchSize(int batchSize) {
    this.batchSize = batchSize;
  }

  public int getMaxAttempts() {
    return maxAttempts;
  }

  public void setMaxAttempts(int maxAttempts) {
    this.maxAttempts = maxAttempts;
  }

  public Duration getRetryBackoff() {
    return retryBackoff;
  }

  public void setRetryBackoff(Duration retryBackoff) {
    this.retryBackoff = retryBackoff;
  }

  public Duration getRecoveryInterval() {
    return recoveryInterval;
  }

  public void setRecoveryInterval(Duration recoveryInterval) {
    this.recoveryInterval = recoveryInterval;
  }

  public boolean isRecoveryEnabled() {
    return recoveryEnabled;
  }

  public void setRecoveryEnabled(boolean recoveryEnabled) {
    this.recoveryEnabled = recoveryEnabled;
  }

  public Provider getProvider() {
    return provider;
  }

  public void setProvider(Provider provider) {
    this.provider = provider;
  }
}
