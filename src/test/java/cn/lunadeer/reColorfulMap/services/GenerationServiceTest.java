package cn.lunadeer.reColorfulMap.services;

import cn.lunadeer.reColorfulMap.Configuration;
import cn.lunadeer.reColorfulMap.Language;
import cn.lunadeer.reColorfulMap.storage.MapStorage;
import cn.lunadeer.reColorfulMap.storage.PendingDeliveryStore;
import cn.lunadeer.reColorfulMap.storage.PendingDeliveryStore.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GenerationServiceTest {
    @TempDir Path directory;
    MapStorage storage;
    PendingDeliveryStore receipts;
    MapStorage.Artwork artwork;
    GenerationService.Platform platform;
    GenerationService.Generator generator;
    GenerationService service;
    Player player;
    ItemStack item;

    @BeforeEach void setUp() throws Exception {
        storage = new MapStorage(directory); receipts = new PendingDeliveryStore(directory);
        BufferedImage tile = new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB);
        artwork = storage.create(tile, new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB), tile);
        player = player(); item = mock(ItemStack.class);
        platform = mock(GenerationService.Platform.class); generator = mock(GenerationService.Generator.class);
        when(generator.generate(anyString(), anyFloat(), any())).thenReturn(artwork);
        when(platform.item(any(), any(), any())).thenReturn(item); when(platform.balance(any())).thenReturn(1000d);
        doAnswer(call -> { call.<Runnable>getArgument(1).run(); return null; }).when(platform).schedule(any(), any(), any());
        service = new GenerationService(storage, receipts, GenerationService.executor(1, 1), generator, platform);
    }
    @AfterEach void close() { service.close(); }
    Player player() { Player p = mock(Player.class); when(p.getUniqueId()).thenReturn(UUID.randomUUID()); return p; }
    Entry entry() { return receipts.find(artwork.id()).orElseThrow(); }
    void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(condition.getAsBoolean(), "Timed out waiting for worker outcome");
    }
    void claimReceipt(double price) throws Exception { receipts.create(artwork.id(), player.getUniqueId(), price); service.claim(player, artwork.id()); }

    @Test void generationQuotesTilesOnceAndDeliveredReceiptBlocksAnotherClaim() throws Exception {
        Configuration.Snapshot old = Configuration.snapshot();
        Configuration.Snapshot charging = new Configuration.Snapshot(old.maxFrameX(), old.maxFrameY(), old.language(), old.debug(),
                old.addressWhiteList(), new Configuration.EconomySettings(true, 7), old.download(), old.generation());
        try (MockedStatic<Configuration> config = mockStatic(Configuration.class)) {
            config.when(Configuration::snapshot).thenReturn(charging);
            service.submit(player, "https://example.test/a.png", 1);
        }
        await(() -> receipts.find(artwork.id()).map(e -> e.delivery() == Delivery.DELIVERED).orElse(false));
        assertEquals(28, entry().amount()); assertEquals(Payment.PAID, entry().payment());
        service.claim(player, artwork.id());
        verify(platform, times(1)).withdraw(player, 28); verify(platform, times(1)).deliver(player, item);
        verify(platform).error(player, Language.mapManage.alreadyDelivered);
    }
    @Test void offlineGenerationIsDurableAndRestartedClaimChargesOnlyOnce() throws Exception {
        doAnswer(call -> { call.<Runnable>getArgument(2).run(); return null; }).when(platform).schedule(any(), any(), any());
        service.submit(player, "https://example.test/a.png", 1);
        await(() -> receipts.find(artwork.id()).isPresent() && service.status(player.getUniqueId()).equals(Language.mapManage.idle));
        assertEquals(Delivery.PENDING, entry().delivery()); verify(platform, never()).deliver(any(), any());
        service.close(); receipts = new PendingDeliveryStore(directory);
        doAnswer(call -> { call.<Runnable>getArgument(1).run(); return null; }).when(platform).schedule(any(), any(), any());
        service = new GenerationService(storage, receipts, GenerationService.executor(1, 1), generator, platform);
        service.claim(player, artwork.id());
        await(() -> entry().delivery() == Delivery.DELIVERED);
        verify(platform, times(1)).deliver(player, item); verify(platform, never()).withdraw(any(), anyDouble());
    }
    @Test void queueAndPerPlayerLimitsRejectWithoutCreatingAnotherJob() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(generator.generate(anyString(), anyFloat(), any())).thenAnswer(call -> { entered.countDown(); release.await(); throw new IOException("stopped"); });
        Player second = player(), third = player();
        service.submit(player, "first", 1); assertTrue(entered.await(5, TimeUnit.SECONDS));
        service.submit(player, "duplicate", 1); service.submit(second, "queued", 1); service.submit(third, "overflow", 1);
        verify(platform).error(third, Language.toMapCommand.serverBusy);
        assertEquals(Language.mapManage.queued, service.status(second.getUniqueId()));
        assertTrue(service.cancel(second.getUniqueId()));
        assertEquals(Language.mapManage.idle, service.status(second.getUniqueId()));
        service.submit(third, "now-accepted", 1);
        assertEquals(Language.mapManage.queued, service.status(third.getUniqueId()));
        service.cancel(third.getUniqueId()); assertTrue(service.cancel(player.getUniqueId()));
        await(() -> service.status(player.getUniqueId()).equals(Language.mapManage.idle));
        verify(generator, times(1)).generate(anyString(), anyFloat(), any());
        verify(platform, never()).withdraw(any(), anyDouble());
    }
    @Test void zeroQueueCapacityRejectsAnotherPlayerWhileTheOnlyWorkerIsBusy() throws Exception {
        service.close();
        service = new GenerationService(storage, receipts, GenerationService.executor(1, 0), generator, platform);
        CountDownLatch entered = new CountDownLatch(1);
        when(generator.generate(anyString(), anyFloat(), any())).thenAnswer(call -> {
            entered.countDown(); new CountDownLatch(1).await(); return artwork;
        });
        service.submit(player, "first", 1); assertTrue(entered.await(5, TimeUnit.SECONDS));
        Player second = player(); service.submit(second, "no-queue", 1);
        verify(platform).error(second, Language.toMapCommand.serverBusy);
        assertEquals(Language.mapManage.idle, service.status(second.getUniqueId()));
    }
    @Test void explicitRejectedChargeReturnsToUnpaidPendingAndCanBeRetried() throws Exception {
        doThrow(new cn.lunadeer.reColorfulMap.utils.VaultConnect.TransactionRejectedException("insufficient funds"))
                .doNothing().when(platform).withdraw(player, 30);
        claimReceipt(30);
        await(() -> mockingDetails(platform).getInvocations().stream().anyMatch(i -> i.getMethod().getName().equals("error")));
        assertEquals(Payment.UNPAID, entry().payment()); assertEquals(Delivery.PENDING, entry().delivery());
        // The failure callback occurs just before releasing the claim reservation.
        await(() -> { service.claim(player, artwork.id()); return entry().delivery() == Delivery.DELIVERED; });
        verify(platform, times(2)).withdraw(player, 30); verify(platform, times(1)).deliver(player, item);
        verify(platform, never()).refund(any(), anyDouble());
    }
    @Test void simultaneousClaimsCannotChargeOrDeliverTwice() throws Exception {
        receipts.create(artwork.id(), player.getUniqueId(), 12);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        doAnswer(call -> { entered.countDown(); release.await(); return null; }).when(platform).deliver(player, item);
        service.claim(player, artwork.id()); assertTrue(entered.await(5, TimeUnit.SECONDS));
        service.claim(player, artwork.id()); release.countDown();
        await(() -> entry().delivery() == Delivery.DELIVERED);
        verify(platform, times(1)).withdraw(player, 12); verify(platform, times(1)).deliver(player, item);
    }
    @Test void cancelledSpawnKeepsPaymentUntilTransferToAnotherInventoryCanBeRuledOut() throws Exception {
        doThrow(new IOException("spawn cancelled")).when(platform).deliver(player, item);
        claimReceipt(20);
        await(() -> mockingDetails(platform).getInvocations().stream().anyMatch(i -> i.getMethod().getName().equals("error")));
        assertEquals(Payment.PAID, entry().payment());
        assertEquals(Delivery.DELIVERING, entry().delivery()); assertTrue(entry().uncertain());
        service.claim(player, artwork.id());
        verify(platform, times(1)).withdraw(player, 20); verify(platform, never()).refund(any(), anyDouble());
        verify(platform, times(1)).deliver(player, item);
        assertNotNull(storage.read(MapStorage.ImageReference.thumbnail(artwork.id())));
    }
    void retireBeforeTheFirstInventoryMutation() {
        AtomicInteger callbacks = new AtomicInteger();
        doAnswer(call -> {
            call.<Runnable>getArgument(callbacks.incrementAndGet() == 3 ? 2 : 1).run();
            return null;
        }).when(platform).schedule(any(), any(), any());
    }
    @Test void knownFailureBeforeInventoryMutationRefundsAndKeepsTheImagePending() throws Exception {
        retireBeforeTheFirstInventoryMutation();
        claimReceipt(20);
        await(() -> mockingDetails(platform).getInvocations().stream().anyMatch(i -> i.getMethod().getName().equals("error")));
        assertEquals(Delivery.PENDING, entry().delivery()); assertEquals(Payment.REFUNDED, entry().payment());
        verify(platform, times(1)).withdraw(player, 20); verify(platform, times(1)).refund(player, 20);
        verify(platform, never()).deliver(any(), any());
    }
    @Test void failedRefundStaysUncertainAndCannotChargeAgain() throws Exception {
        retireBeforeTheFirstInventoryMutation();
        doThrow(new IllegalStateException("provider rejected refund")).when(platform).refund(player, 15);
        claimReceipt(15);
        await(() -> entry().payment() == Payment.REFUNDING);
        service.claim(player, artwork.id());
        verify(platform, times(1)).withdraw(player, 15);
        await(() -> {
            try { service.resolveNow(artwork.id(), "pending"); return false; }
            catch (IllegalArgumentException expected) { return true; }
            catch (IOException claimStillCompleting) { return false; }
        });
    }
    @Test void unknownInventoryOutcomeKeepsPaymentFrozenInsteadOfGivingAwayAFreeMap() throws Exception {
        doThrow(new IOException("inventory listener failed after moving item")).when(platform).deliver(player, item);
        claimReceipt(25);
        await(() -> mockingDetails(platform).getInvocations().stream().anyMatch(i -> i.getMethod().getName().equals("error")));
        assertEquals(Delivery.DELIVERING, entry().delivery()); assertEquals(Payment.PAID, entry().payment());
        verify(platform, never()).refund(any(), anyDouble());
        service.claim(player, artwork.id()); verify(platform, times(1)).deliver(player, item);
    }
    @Test void explicitRefundRejectionKeepsPaidCreditAndReviewDoesNotChargeAgain() throws Exception {
        retireBeforeTheFirstInventoryMutation();
        doThrow(new cn.lunadeer.reColorfulMap.utils.VaultConnect.TransactionRejectedException("refund refused"))
                .when(platform).refund(player, 15);
        claimReceipt(15);
        await(() -> mockingDetails(platform).getInvocations().stream().anyMatch(i -> i.getMethod().getName().equals("error")));
        assertEquals(Payment.PAID, entry().payment()); assertEquals(Delivery.PENDING, entry().delivery());
        // Wait for the current claim to release its reservation before administrative reconciliation.
        await(() -> {
            try { service.resolveNow(artwork.id(), "pending"); return true; } catch (IOException busy) { return false; }
        });
        service.claim(player, artwork.id()); await(() -> entry().delivery() == Delivery.DELIVERED);
        verify(platform, times(1)).withdraw(player, 15); verify(platform, times(1)).deliver(player, item);
    }
    @Test void administratorCanConfirmAnUncertainRefundDidNotHappenWithoutLosingPaidCredit() throws Exception {
        Entry original = receipts.create(artwork.id(), player.getUniqueId(), 15);
        receipts.update(original, e -> e.delivery(Delivery.DELIVERING).payment(Payment.REFUNDING));
        service.resolveNow(artwork.id(), "paid"); service.resolveNow(artwork.id(), "pending");
        service.claim(player, artwork.id()); await(() -> entry().delivery() == Delivery.DELIVERED);
        verify(platform, never()).withdraw(any(), anyDouble()); verify(platform, never()).refund(any(), anyDouble());
    }
    @Test void alreadyPaidRecoveredReceiptDoesNotChargeAgain() throws Exception {
        Entry receipt = receipts.create(artwork.id(), player.getUniqueId(), 30);
        receipts.update(receipt, e -> e.payment(Payment.PAID));
        service.claim(player, artwork.id());
        await(() -> entry().delivery() == Delivery.DELIVERED);
        verify(platform, never()).withdraw(any(), anyDouble()); verify(platform).deliver(player, item);
    }
    @Test void failedWithdrawIsNotRetriedAfterRestartWithoutTransactionReview() throws Exception {
        doThrow(new IllegalStateException("unknown provider result")).when(platform).withdraw(player, 30);
        claimReceipt(30);
        await(() -> mockingDetails(platform).getInvocations().stream().anyMatch(i -> i.getMethod().getName().equals("error")));
        assertEquals(Payment.CHARGING, entry().payment());
        service.close(); receipts = new PendingDeliveryStore(directory);
        service = new GenerationService(storage, receipts, GenerationService.executor(1, 1), generator, platform);
        service.claim(player, artwork.id());
        verify(platform, times(1)).withdraw(player, 30); verify(platform, never()).deliver(any(), any());
        assertTrue(entry().uncertain());
    }
    @Test void lowBalancePreservesAnUnpaidPendingMap() throws Exception {
        when(platform.balance(player)).thenReturn(2d);
        claimReceipt(100);
        await(() -> mockingDetails(platform).getInvocations().stream().anyMatch(i -> i.getMethod().getName().equals("error")));
        assertEquals(Delivery.PENDING, entry().delivery()); assertEquals(Payment.UNPAID, entry().payment());
        verify(platform, never()).withdraw(any(), anyDouble()); verify(platform, never()).deliver(any(), any());
    }
    @Test void anotherPlayerCannotClaimOrReassignTheReceipt() throws Exception {
        receipts.create(artwork.id(), player.getUniqueId(), 5);
        Player stranger = player(); service.claim(stranger, artwork.id()); service.recover(stranger, artwork.id());
        verify(platform, times(2)).error(stranger, Language.mapManage.notOwned);
        assertEquals(player.getUniqueId(), entry().owner()); verify(platform, never()).deliver(any(), any());
    }
    @Test void cancellationAfterPngCommitKeepsARecoveryReceiptAndNeverCharges() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        when(generator.generate(anyString(), anyFloat(), any())).thenAnswer(call -> {
            entered.countDown();
            try { new CountDownLatch(1).await(); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
            return artwork;
        });
        service.submit(player, "generated-before-cancel", 1); assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertTrue(service.cancel(player.getUniqueId()));
        await(() -> receipts.find(artwork.id()).isPresent());
        assertEquals(Delivery.PENDING, entry().delivery()); verify(platform, never()).withdraw(any(), anyDouble()); verify(platform, never()).deliver(any(), any());
    }
    @Test void cancellationDuringReceiptCommitCannotInterruptOrPoisonTheStore() throws Exception {
        AtomicReference<Thread> worker = new AtomicReference<>();
        when(generator.generate(anyString(), anyFloat(), any())).thenAnswer(call -> { worker.set(Thread.currentThread()); return artwork; });
        synchronized (receipts) {
            service.submit(player, "already-committed-png", 1);
            await(() -> worker.get() != null && worker.get().getState() == Thread.State.BLOCKED);
            assertTrue(service.cancel(player.getUniqueId()));
        }
        await(() -> receipts.find(artwork.id()).isPresent());
        assertEquals(Delivery.PENDING, entry().delivery());
        assertDoesNotThrow(() -> receipts.create(UUID.randomUUID(), player.getUniqueId(), 0));
        verify(platform, never()).deliver(any(), any());
    }
    @Test void shutdownDuringReceiptCommitCannotInterruptOrPoisonTheStore() throws Exception {
        AtomicReference<Thread> worker = new AtomicReference<>();
        when(generator.generate(anyString(), anyFloat(), any())).thenAnswer(call -> { worker.set(Thread.currentThread()); return artwork; });
        synchronized (receipts) {
            service.submit(player, "already-committed-png", 1);
            await(() -> worker.get() != null && worker.get().getState() == Thread.State.BLOCKED);
            service.close();
        }
        await(() -> receipts.find(artwork.id()).isPresent());
        assertEquals(Delivery.PENDING, entry().delivery());
        assertDoesNotThrow(() -> receipts.create(UUID.randomUUID(), player.getUniqueId(), 0));
        verify(platform, never()).deliver(any(), any());
    }
    @Test void shutdownInvalidatesLateOwnerCallbacksAndLeavesRestartablePendingData() throws Exception {
        AtomicReference<Runnable> callback = new AtomicReference<>();
        doAnswer(call -> { callback.set(call.getArgument(1)); return null; }).when(platform).schedule(any(), any(), any());
        service.submit(player, "late-callback", 1);
        await(() -> callback.get() != null);
        service.close(); callback.get().run();
        assertEquals(Delivery.PENDING, new PendingDeliveryStore(directory).find(artwork.id()).orElseThrow().delivery());
        verify(platform, never()).item(any(), any(), any()); verify(platform, never()).withdraw(any(), anyDouble());
    }
    @Test void explicitReviewCannotResetACompletedReceipt() throws Exception {
        Entry receipt = receipts.create(artwork.id(), player.getUniqueId(), 0);
        receipts.update(receipt, e -> e.delivery(Delivery.DELIVERED));
        assertThrows(IOException.class, () -> service.resolveNow(artwork.id(), "pending"));
        service.recover(player, artwork.id()); verify(platform, never()).deliver(any(), any());
    }
}
