package dev.donut.addon.expansion.automation;

import dev.donut.addon.expansion.automation.core.AuctionPurchaseConfirmation;
import dev.donut.addon.expansion.automation.core.AuctionPurchaseConfirmationAction;
import dev.donut.addon.expansion.automation.core.AuctionPurchaseEffect;
import dev.donut.addon.expansion.automation.core.AuctionPurchaseFrame;
import dev.donut.addon.expansion.automation.core.AuctionPurchaseProgress;
import dev.donut.addon.expansion.automation.core.AuctionPurchaseRequest;
import dev.donut.addon.expansion.automation.core.AuctionPurchaseReceipt;
import dev.donut.addon.expansion.automation.core.AuctionPurchaseReceiptKind;
import dev.donut.addon.expansion.automation.core.AuctionPurchaseStatus;
import dev.donut.addon.expansion.automation.core.AuctionPurchaseWorkflow;
import dev.donut.addon.expansion.automation.core.AuctionPurchaseListingRules;
import dev.donut.addon.expansion.automation.core.AutomationSnapshot;
import dev.donut.addon.expansion.automation.core.DialogSnapshot;
import dev.donut.addon.expansion.automation.core.DialogWidgetSnapshot;
import dev.donut.addon.expansion.automation.core.InventorySlotValue;
import dev.donut.addon.expansion.automation.core.InventoryIncreaseAckTracker;
import dev.donut.addon.expansion.automation.core.InventoryPickupAction;
import dev.donut.addon.expansion.automation.core.InventoryStagePlan;
import dev.donut.addon.expansion.automation.core.InventoryTransferMachine;
import dev.donut.addon.expansion.automation.core.InventoryTransferProgress;
import dev.donut.addon.expansion.automation.core.InventorySyncFrame;
import dev.donut.addon.expansion.automation.core.InventoryAckStatus;
import dev.donut.addon.expansion.automation.core.MenuSnapshot;
import dev.donut.addon.expansion.automation.core.SessionStamp;
import dev.donut.addon.expansion.automation.core.SlotSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuctionPurchaseWorkflowTest {
    private static final SessionStamp SESSION = new SessionStamp(4L, 9L, 2L, 7L);
    private static final String ITEM = "minecraft:golden_apple";
    private static final String COMPONENTS = "components[damage=0]";

    @BeforeAll
    static void bootstrapMinecraftData() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        // Test JVM only: bind Mojang's vanilla item defaults so native ItemStack identity can be compared.
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(VanillaRegistries.createWorldLookup())
            .forEach(initializer -> initializer.apply());
    }

    @Test
    void purchasesWholeListingWhenItOvershootsMinimumAndRequiresFullInventoryIncrease() {
        AuctionPurchaseWorkflow workflow = new AuctionPurchaseWorkflow();
        List<AuctionPurchaseEffect> effects = new ArrayList<>();
        var host = (dev.donut.addon.expansion.automation.core.AuctionPurchaseHost)
            (frame, effect) -> { effects.add(effect); return true; };
        assertTrue(workflow.start(new AuctionPurchaseRequest("golden_apple", 5, new BigDecimal("1000"), Map.of(),
            false, AuctionPurchaseListingRules::ordinary, new BigDecimal("700"))));

        workflow.tick(frame(0, null, null, null, 1, 0, null, null), host);
        AuctionPurchaseFrame auction = auctionFrame(1, 12, 10, appleListing(64, "$640"), 1, null, null);
        workflow.tick(auction, host);
        workflow.tick(auctionFrame(2, 12, 10, appleListing(64, "$640"), 1, null, null), host);
        workflow.tick(auctionFrame(4, 12, 10, appleListing(64, "$640"), 1, null, null), host);

        AuctionPurchaseEffect.ClickListing click = effects.stream()
            .filter(AuctionPurchaseEffect.ClickListing.class::isInstance)
            .map(AuctionPurchaseEffect.ClickListing.class::cast)
            .findFirst().orElseThrow();
        assertEquals(0, click.getSlotIndex());

        workflow.observeInventory(sync(12, 11, 32, COMPONENTS), 1L);
        AuctionPurchaseProgress partial = workflow.tick(
            auctionFrame(5, 12, 11, appleListing(64, "$640"), 1,
                inventoryWith(ITEM, 32, COMPONENTS), null), host);
        assertEquals(AuctionPurchaseStatus.RUNNING, partial.getStatus());
        assertEquals(0, partial.getAcquiredAmount());

        workflow.observeInventory(sync(12, 12, 64, COMPONENTS), 1L);
        AuctionPurchaseProgress complete = workflow.tick(
            auctionFrame(6, 12, 12, appleListing(64, "$640"), 1,
                inventoryWith(ITEM, 64, COMPONENTS), null), host);
        assertEquals(AuctionPurchaseStatus.COMPLETE, complete.getStatus());
        assertEquals(64, complete.getAcquiredAmount());
        assertEquals(new BigDecimal("640"), complete.getConfirmedSpend());
        assertEquals(new BigDecimal("640"), complete.getReservedSpend());

        workflow.resetRequest();
        AuctionPurchaseRequest secondProduct = new AuctionPurchaseRequest("golden_carrot", 1, new BigDecimal("100"),
            Map.of(), false, AuctionPurchaseListingRules::ordinary, new BigDecimal("700"));
        assertTrue(workflow.start(secondProduct));
        workflow.tick(frame(10, null, null, null, 1, 0, null, null), host);
        List<SlotSnapshot> carrotListing = List.of(new SlotSnapshot(0, "minecraft:golden_carrot", 1, "Golden Carrot",
            List.of("Price: $100", "Seller: AnotherPlayer"), "", Map.of(), 64));
        workflow.tick(auctionFrame(11, 15, 10, carrotListing, 1, null, null), host);
        workflow.tick(auctionFrame(12, 15, 10, carrotListing, 1, null, null), host);
        AuctionPurchaseProgress budget = workflow.tick(auctionFrame(14, 15, 10, carrotListing, 1, null, null), host);
        assertEquals(AuctionPurchaseStatus.BUDGET_EXHAUSTED, budget.getStatus());
        assertEquals(new BigDecimal("640"), budget.getConfirmedSpend());
        assertEquals(new BigDecimal("640"), budget.getReservedSpend());
    }

    @Test
    void invalidConfirmationLeavesUncertainReservationAcrossRequestReset() {
        AuctionPurchaseWorkflow workflow = new AuctionPurchaseWorkflow();
        List<AuctionPurchaseEffect> effects = new ArrayList<>();
        var host = (dev.donut.addon.expansion.automation.core.AuctionPurchaseHost)
            (frame, effect) -> { effects.add(effect); return true; };
        AuctionPurchaseRequest request = new AuctionPurchaseRequest("golden_apple", 1, new BigDecimal("100"));
        assertTrue(workflow.start(request));
        workflow.tick(frame(0, null, null, null, 1, 0, null, null), host);
        workflow.tick(auctionFrame(1, 12, 10, appleListing(1, "$100"), 1, null, null), host);
        workflow.tick(auctionFrame(2, 12, 10, appleListing(1, "$100"), 1, null, null), host);
        workflow.tick(auctionFrame(4, 12, 10, appleListing(1, "$100"), 1, null, null), host);

        DialogWidgetSnapshot confirm = new DialogWidgetSnapshot(0, "button", "Confirm", "");
        DialogSnapshot dialog = new DialogSnapshot(88L, "Confirm Purchase", List.of(confirm));
        AuctionPurchaseConfirmation evidence = new AuctionPurchaseConfirmation(
            ITEM, 1, Map.of(), new BigDecimal("101"), 88L, null, confirm.getFingerprint(),
            new AuctionPurchaseConfirmationAction.DialogWidget(88L, 0, confirm.getFingerprint(), "Confirm"));
        AuctionPurchaseProgress uncertain = workflow.tick(
            frame(5, null, null, dialog, 1, 0, null, evidence), host);
        assertEquals(AuctionPurchaseStatus.UNCERTAIN, uncertain.getStatus());
        assertEquals(new BigDecimal("100"), uncertain.getReservedSpend());
        assertFalse(effects.stream().anyMatch(AuctionPurchaseEffect.ClickConfirmation.class::isInstance));

        workflow.resetRequest();
        assertEquals(new BigDecimal("100"), workflow.progress().getReservedSpend());
        assertFalse(workflow.start(request));
        assertEquals(AuctionPurchaseStatus.UNCERTAIN, workflow.progress().getStatus());
        workflow.resetRun();
        assertEquals(BigDecimal.ZERO, workflow.progress().getReservedSpend());
        assertEquals(AuctionPurchaseStatus.IDLE, workflow.progress().getStatus());
    }

    @Test
    void chestConfirmationMayChangeMenuMapAndUsesCapturedEpochForFullInventoryAck() {
        AuctionPurchaseWorkflow workflow = new AuctionPurchaseWorkflow();
        List<AuctionPurchaseEffect> effects = new ArrayList<>();
        var host = (dev.donut.addon.expansion.automation.core.AuctionPurchaseHost)
            (frame, effect) -> { effects.add(effect); return true; };
        assertTrue(workflow.start(new AuctionPurchaseRequest("golden_apple", 5, new BigDecimal("1000"))));
        workflow.tick(frame(0, null, null, null, 1, 0, null, null), host);
        workflow.tick(auctionFrame(1, 12, 10, appleListing(64, "$640"), 1, null, null), host);
        workflow.tick(auctionFrame(2, 12, 10, appleListing(64, "$640"), 1, null, null), host);
        workflow.tick(auctionFrame(4, 12, 10, appleListing(64, "$640"), 1, null, null), host);

        SlotSnapshot confirmButton = new SlotSnapshot(0, "minecraft:lime_stained_glass_pane", 1,
            "Confirm Purchase", List.of(), "confirm-button", Map.of(), 64);
        MenuSnapshot confirmMenu = new MenuSnapshot(51L, 13, "Confirm Purchase", List.of(confirmButton), true, 20);
        AuctionPurchaseConfirmation confirmation = new AuctionPurchaseConfirmation(
            ITEM, 64, Map.of(), new BigDecimal("640"), 51L, 13, confirmButton.getFingerprint(),
            new AuctionPurchaseConfirmationAction.MenuSlot(13, 51L, 0, confirmButton.getFingerprint()));
        AuctionPurchaseFrame confirmFrame = new AuctionPurchaseFrame(
            new AutomationSnapshot(SESSION, 5, confirmMenu, Set.of(), Set.of(), "", null, emptyInventory(110)),
            "Buyer", 13, Map.of(39, 9), 2L, 1L, 45, null, confirmation);
        assertEquals(AuctionPurchaseStatus.RUNNING, workflow.tick(confirmFrame, host).getStatus());
        assertTrue(effects.stream().anyMatch(AuctionPurchaseEffect.ClickConfirmation.class::isInstance));

        InventorySlotValue purchased = new InventorySlotValue(ITEM, 64, "Golden Apple", List.of(), COMPONENTS, Map.of(), COMPONENTS);
        workflow.observeInventory(new InventorySyncFrame(SESSION.getConnection(), 13, 21,
            Map.of(39, purchased), null, false), 1L);
        workflow.observeInventory(new InventorySyncFrame(SESSION.getConnection(), 13, 22,
            Map.of(39, purchased), null, false), 2L);
        workflow.observeInventory(new InventorySyncFrame(SESSION.getConnection(), 13, 21,
            Map.of(39, new InventorySlotValue(ITEM, 32, "Golden Apple", List.of(), COMPONENTS, Map.of(), COMPONENTS)),
            null, true), 2L);
        AuctionPurchaseProgress complete = workflow.tick(
            frame(6, null, inventoryWith(ITEM, 64, COMPONENTS, 15), null, 3, 2, null, null), host);
        assertEquals(AuctionPurchaseStatus.COMPLETE, complete.getStatus());
        assertEquals(64, complete.getAcquiredAmount());
    }

    @Test
    void repeatedConfirmationMapCaptureKeepsItsOriginalStateBaseline() {
        InventoryIncreaseAckTracker tracker = new InventoryIncreaseAckTracker();
        AuctionPurchaseFrame before = auctionFrame(4, 12, 10, appleListing(64, "$640"), 1, null, null);
        assertTrue(tracker.begin(before, ITEM, Map.of(), COMPONENTS, "Golden Apple", List.of(), 64));

        SlotSnapshot confirmButton = new SlotSnapshot(0, "minecraft:lime_stained_glass_pane", 1,
            "Confirm Purchase", List.of(), "confirm-button", Map.of(), 64);
        MenuSnapshot firstMenu = new MenuSnapshot(51L, 13, "Confirm Purchase", List.of(confirmButton), true, 20);
        AutomationSnapshot firstSnapshot = new AutomationSnapshot(SESSION, 5, firstMenu, Set.of(), Set.of(), "", null,
            emptyInventory(110));
        AuctionPurchaseFrame first = new AuctionPurchaseFrame(firstSnapshot, "Buyer", 13, Map.of(39, 9), 2L, 1L, 45);
        assertTrue(tracker.registerMenuMap(first));

        MenuSnapshot newerMenu = new MenuSnapshot(51L, 13, "Confirm Purchase", List.of(confirmButton), true, 21);
        AutomationSnapshot newerSnapshot = new AutomationSnapshot(SESSION, 6, newerMenu, Set.of(), Set.of(), "", null,
            emptyInventory(111));
        AuctionPurchaseFrame repeated = new AuctionPurchaseFrame(newerSnapshot, "Buyer", 13, Map.of(39, 9), 2L, 2L, 45);
        assertTrue(tracker.registerMenuMap(repeated));

        InventorySlotValue purchased = new InventorySlotValue(ITEM, 64, "Golden Apple", List.of(), COMPONENTS,
            Map.of(), COMPONENTS);
        assertEquals(InventoryAckStatus.PENDING, tracker.observe(new InventorySyncFrame(SESSION.getConnection(), 13, 21,
            Map.of(39, purchased), null, false), 2L));
        assertEquals(InventoryAckStatus.ACKNOWLEDGED, tracker.status(inventoryWith(ITEM, 64, COMPONENTS)));
    }

    @Test
    void confirmationRetainsActualSelectedVariantIdentity() {
        AuctionPurchaseWorkflow workflow = new AuctionPurchaseWorkflow();
        List<AuctionPurchaseEffect> effects = new ArrayList<>();
        var host = (dev.donut.addon.expansion.automation.core.AuctionPurchaseHost)
            (frame, effect) -> { effects.add(effect); return true; };
        AuctionPurchaseRequest variantRequest = new AuctionPurchaseRequest("glass", 1, new BigDecimal("100"),
            Map.of(), false, AuctionPurchaseListingRules::ordinary, null, 5, 5, 2L, 0L, 60L,
            Set.of("glass", "oak_leaves"));
        assertTrue(workflow.start(variantRequest));
        workflow.tick(frame(0, null, null, null, 1, 0, null, null), host);
        List<SlotSnapshot> variants = List.of(new SlotSnapshot(0, "minecraft:oak_leaves", 16, "Oak Leaves",
            List.of("Price: $16", "Seller: AnotherPlayer"), COMPONENTS, Map.of(), 64));
        workflow.tick(auctionFrame(1, 12, 10, variants, 1, null, null), host);
        workflow.tick(auctionFrame(2, 12, 10, variants, 1, null, null), host);
        workflow.tick(auctionFrame(4, 12, 10, variants, 1, null, null), host);

        DialogWidgetSnapshot confirm = new DialogWidgetSnapshot(0, "button", "Confirm", "");
        DialogSnapshot dialog = new DialogSnapshot(89L, "Confirm Purchase", List.of(confirm));
        AuctionPurchaseConfirmation wrongItem = new AuctionPurchaseConfirmation(
            "glass", 16, Map.of(), new BigDecimal("16"), 89L, null, confirm.getFingerprint(),
            new AuctionPurchaseConfirmationAction.DialogWidget(89L, 0, confirm.getFingerprint(), "Confirm"));
        AuctionPurchaseProgress uncertain = workflow.tick(
            frame(5, null, null, dialog, 1, 0, null, wrongItem), host);
        assertEquals(AuctionPurchaseStatus.UNCERTAIN, uncertain.getStatus());
        assertEquals(new BigDecimal("16"), uncertain.getReservedSpend());
        assertFalse(effects.stream().anyMatch(AuctionPurchaseEffect.ClickConfirmation.class::isInstance));
    }

    @Test
    void dialogConfirmationCanCompleteAfterExactInventoryAck() {
        AuctionPurchaseWorkflow workflow = new AuctionPurchaseWorkflow();
        List<AuctionPurchaseEffect> effects = new ArrayList<>();
        var host = (dev.donut.addon.expansion.automation.core.AuctionPurchaseHost)
            (frame, effect) -> { effects.add(effect); return true; };
        assertTrue(workflow.start(new AuctionPurchaseRequest("golden_apple", 1, new BigDecimal("100"))));
        workflow.tick(frame(0, null, null, null, 1, 0, null, null), host);
        workflow.tick(auctionFrame(1, 12, 10, appleListing(1, "$10"), 1, null, null), host);
        workflow.tick(auctionFrame(2, 12, 10, appleListing(1, "$10"), 1, null, null), host);
        workflow.tick(auctionFrame(4, 12, 10, appleListing(1, "$10"), 1, null, null), host);

        DialogWidgetSnapshot confirm = new DialogWidgetSnapshot(0, "button", "Confirm", "");
        DialogSnapshot dialog = new DialogSnapshot(90L, "Confirm Purchase", List.of(confirm));
        AuctionPurchaseConfirmation evidence = new AuctionPurchaseConfirmation(
            ITEM, 1, Map.of(), new BigDecimal("10"), 90L, null, confirm.getFingerprint(),
            new AuctionPurchaseConfirmationAction.DialogWidget(90L, 0, confirm.getFingerprint(), "Confirm"));
        AuctionPurchaseProgress waiting = workflow.tick(
            frame(5, null, null, dialog, 1, 0, null, evidence), host);
        assertEquals(AuctionPurchaseStatus.RUNNING, waiting.getStatus());
        assertTrue(effects.stream().anyMatch(AuctionPurchaseEffect.ClickConfirmation.class::isInstance));

        workflow.observeInventory(sync(12, 11, 1, COMPONENTS), 1L);
        AuctionPurchaseProgress complete = workflow.tick(
            frame(6, null, inventoryWith(ITEM, 1, COMPONENTS, 15), null, 2, 1, null, null), host);
        assertEquals(AuctionPurchaseStatus.COMPLETE, complete.getStatus());
        assertEquals(1, complete.getAcquiredAmount());
    }

    @Test
    void correlatedPurchaseReceiptSuppliesActualSpendAndRequiresInventoryAck() {
        AuctionPurchaseWorkflow workflow = new AuctionPurchaseWorkflow();
        List<AuctionPurchaseEffect> effects = new ArrayList<>();
        var host = (dev.donut.addon.expansion.automation.core.AuctionPurchaseHost)
            (frame, effect) -> { effects.add(effect); return true; };
        assertTrue(workflow.start(new AuctionPurchaseRequest("golden_apple", 1, new BigDecimal("100"), Map.of(),
            false, AuctionPurchaseListingRules::ordinary, new BigDecimal("20"))));
        workflow.tick(frame(0, null, null, null, 1, 0, null, null), host);
        workflow.tick(auctionFrame(1, 12, 10, appleListing(1, "$10"), 1, null, null), host);
        workflow.tick(auctionFrame(2, 12, 10, appleListing(1, "$10"), 1, null, null), host);
        workflow.tick(auctionFrame(4, 12, 10, appleListing(1, "$10"), 1, null, null), host);
        AuctionPurchaseEffect.ClickListing click = effects.stream()
            .filter(AuctionPurchaseEffect.ClickListing.class::isInstance)
            .map(AuctionPurchaseEffect.ClickListing.class::cast)
            .findFirst().orElseThrow();

        AuctionPurchaseReceipt stale = new AuctionPurchaseReceipt(9L, new SessionStamp(4L, 10L, 2L, 7L),
            click.getAttemptId(), AuctionPurchaseReceiptKind.PURCHASED, "Golden Apple", null, 1, new BigDecimal("12"));
        AuctionPurchaseReceipt receipt = new AuctionPurchaseReceipt(1L, SESSION, click.getAttemptId(),
            AuctionPurchaseReceiptKind.PURCHASED, "Golden Apple", null, 1, new BigDecimal("12"));
        AuctionPurchaseProgress waiting = workflow.tick(withReceipt(
            auctionFrame(5, 12, 11, appleListing(1, "$10"), 1, null, null), receipt, stale), host);
        assertEquals(AuctionPurchaseStatus.RUNNING, waiting.getStatus());
        assertEquals(new BigDecimal("12"), waiting.getReservedSpend());
        assertEquals(0, waiting.getAcquiredAmount());

        workflow.observeInventory(sync(12, 12, 1, COMPONENTS), 1L);
        AuctionPurchaseProgress complete = workflow.tick(
            auctionFrame(6, 12, 12, appleListing(1, "$10"), 1, inventoryWith(ITEM, 1, COMPONENTS), null), host);
        assertEquals(AuctionPurchaseStatus.COMPLETE, complete.getStatus());
        assertEquals(new BigDecimal("12"), complete.getConfirmedSpend());
    }

    @Test
    void confirmationAndReceiptTotalsCompareNumericallyAcrossDecimalScales() {
        AuctionPurchaseWorkflow workflow = new AuctionPurchaseWorkflow();
        List<AuctionPurchaseEffect> effects = new ArrayList<>();
        var host = (dev.donut.addon.expansion.automation.core.AuctionPurchaseHost)
            (frame, effect) -> { effects.add(effect); return true; };
        assertTrue(workflow.start(new AuctionPurchaseRequest("golden_apple", 1, new BigDecimal("100"))));
        workflow.tick(frame(0, null, null, null, 1, 0, null, null), host);
        workflow.tick(auctionFrame(1, 12, 10, appleListing(1, "$10"), 1, null, null), host);
        workflow.tick(auctionFrame(2, 12, 10, appleListing(1, "$10"), 1, null, null), host);
        workflow.tick(auctionFrame(4, 12, 10, appleListing(1, "$10"), 1, null, null), host);
        AuctionPurchaseEffect.ClickListing click = effects.stream()
            .filter(AuctionPurchaseEffect.ClickListing.class::isInstance)
            .map(AuctionPurchaseEffect.ClickListing.class::cast)
            .findFirst().orElseThrow();

        DialogWidgetSnapshot confirm = new DialogWidgetSnapshot(0, "button", "Confirm", "");
        DialogSnapshot dialog = new DialogSnapshot(91L, "Confirm Purchase", List.of(confirm));
        AuctionPurchaseConfirmation evidence = new AuctionPurchaseConfirmation(
            ITEM, 1, Map.of(), new BigDecimal("10.00"), 91L, null, confirm.getFingerprint(),
            new AuctionPurchaseConfirmationAction.DialogWidget(91L, 0, confirm.getFingerprint(), "Confirm"));
        AuctionPurchaseProgress waiting = workflow.tick(
            frame(5, null, null, dialog, 1, 0, null, evidence), host);
        assertEquals(AuctionPurchaseStatus.RUNNING, waiting.getStatus());

        AuctionPurchaseReceipt receipt = new AuctionPurchaseReceipt(1L, SESSION, click.getAttemptId(),
            AuctionPurchaseReceiptKind.PURCHASED, "Golden Apple", null, 1, new BigDecimal("10"));
        workflow.observeInventory(sync(12, 11, 1, COMPONENTS), 1L);
        AuctionPurchaseProgress complete = workflow.tick(withReceipt(
            frame(6, null, inventoryWith(ITEM, 1, COMPONENTS, 15), null, 2, 1, null, null), receipt), host);
        assertEquals(AuctionPurchaseStatus.COMPLETE, complete.getStatus());
        assertEquals(0, new BigDecimal("10").compareTo(complete.getConfirmedSpend()));
    }

    @Test
    void overBudgetReceiptBecomesUncertainAndReservesReportedSpend() {
        AuctionPurchaseWorkflow workflow = new AuctionPurchaseWorkflow();
        List<AuctionPurchaseEffect> effects = new ArrayList<>();
        var host = (dev.donut.addon.expansion.automation.core.AuctionPurchaseHost)
            (frame, effect) -> { effects.add(effect); return true; };
        assertTrue(workflow.start(new AuctionPurchaseRequest("golden_apple", 1, new BigDecimal("100"), Map.of(),
            false, AuctionPurchaseListingRules::ordinary, new BigDecimal("10"))));
        workflow.tick(frame(0, null, null, null, 1, 0, null, null), host);
        workflow.tick(auctionFrame(1, 12, 10, appleListing(1, "$9"), 1, null, null), host);
        workflow.tick(auctionFrame(2, 12, 10, appleListing(1, "$9"), 1, null, null), host);
        workflow.tick(auctionFrame(4, 12, 10, appleListing(1, "$9"), 1, null, null), host);
        AuctionPurchaseEffect.ClickListing click = effects.stream()
            .filter(AuctionPurchaseEffect.ClickListing.class::isInstance)
            .map(AuctionPurchaseEffect.ClickListing.class::cast)
            .findFirst().orElseThrow();

        AuctionPurchaseReceipt receipt = new AuctionPurchaseReceipt(2L, SESSION, click.getAttemptId(),
            AuctionPurchaseReceiptKind.PURCHASED, "Golden Apple", ITEM, 1, new BigDecimal("11"));
        AuctionPurchaseProgress result = workflow.tick(withReceipt(
            auctionFrame(5, 12, 11, appleListing(1, "$9"), 1, null, null), receipt), host);
        assertEquals(AuctionPurchaseStatus.UNCERTAIN, result.getStatus());
        assertEquals(new BigDecimal("11"), result.getReservedSpend());
        assertFalse(workflow.start(new AuctionPurchaseRequest("golden_apple", 1, new BigDecimal("100"))));
    }

    @Test
    void receiptMustActuallyNameTheClickedProduct() {
        AuctionPurchaseWorkflow workflow = new AuctionPurchaseWorkflow();
        List<AuctionPurchaseEffect> effects = new ArrayList<>();
        var host = (dev.donut.addon.expansion.automation.core.AuctionPurchaseHost)
            (frame, effect) -> { effects.add(effect); return true; };
        assertTrue(workflow.start(new AuctionPurchaseRequest("golden_apple", 1, new BigDecimal("100"))));
        workflow.tick(frame(0, null, null, null, 1, 0, null, null), host);
        workflow.tick(auctionFrame(1, 12, 10, appleListing(1, "$10"), 1, null, null), host);
        workflow.tick(auctionFrame(2, 12, 10, appleListing(1, "$10"), 1, null, null), host);
        workflow.tick(auctionFrame(4, 12, 10, appleListing(1, "$10"), 1, null, null), host);
        AuctionPurchaseEffect.ClickListing click = effects.stream()
            .filter(AuctionPurchaseEffect.ClickListing.class::isInstance)
            .map(AuctionPurchaseEffect.ClickListing.class::cast)
            .findFirst().orElseThrow();

        AuctionPurchaseReceipt wrongName = new AuctionPurchaseReceipt(3L, SESSION, click.getAttemptId(),
            AuctionPurchaseReceiptKind.PURCHASED, "Diamond", ITEM, 1, new BigDecimal("10"));
        AuctionPurchaseProgress result = workflow.tick(withReceipt(
            auctionFrame(5, 12, 11, appleListing(1, "$10"), 1, null, null), wrongName), host);
        assertEquals(AuctionPurchaseStatus.UNCERTAIN, result.getStatus());
        assertEquals(new BigDecimal("10"), result.getReservedSpend());
    }

    @Test
    void rejectionReceiptWithContradictoryNameIsUncertain() {
        AuctionPurchaseWorkflow workflow = new AuctionPurchaseWorkflow();
        List<AuctionPurchaseEffect> effects = new ArrayList<>();
        var host = (dev.donut.addon.expansion.automation.core.AuctionPurchaseHost)
            (frame, effect) -> { effects.add(effect); return true; };
        assertTrue(workflow.start(new AuctionPurchaseRequest("golden_apple", 1, new BigDecimal("100"))));
        workflow.tick(frame(0, null, null, null, 1, 0, null, null), host);
        workflow.tick(auctionFrame(1, 12, 10, appleListing(1, "$10"), 1, null, null), host);
        workflow.tick(auctionFrame(2, 12, 10, appleListing(1, "$10"), 1, null, null), host);
        workflow.tick(auctionFrame(4, 12, 10, appleListing(1, "$10"), 1, null, null), host);
        AuctionPurchaseEffect.ClickListing click = effects.stream()
            .filter(AuctionPurchaseEffect.ClickListing.class::isInstance)
            .map(AuctionPurchaseEffect.ClickListing.class::cast)
            .findFirst().orElseThrow();

        AuctionPurchaseReceipt wrongName = new AuctionPurchaseReceipt(4L, SESSION, click.getAttemptId(),
            AuctionPurchaseReceiptKind.LISTING_GONE, "Diamond", null, null, null);
        AuctionPurchaseProgress result = workflow.tick(withReceipt(
            auctionFrame(5, 12, 11, appleListing(1, "$10"), 1, null, null), wrongName), host);
        assertEquals(AuctionPurchaseStatus.UNCERTAIN, result.getStatus());
        assertEquals(new BigDecimal("10"), result.getReservedSpend());
    }

    @Test
    void explicitTransactionRejectionsClearReservationAndDistinguishInsufficientFunds() {
        assertReceiptFailure(AuctionPurchaseReceiptKind.INSUFFICIENT_FUNDS, AuctionPurchaseStatus.INSUFFICIENT_FUNDS);
        assertReceiptFailure(AuctionPurchaseReceiptKind.LISTING_GONE, AuctionPurchaseStatus.UNAVAILABLE);
        assertReceiptFailure(AuctionPurchaseReceiptKind.OWN_LISTING, AuctionPurchaseStatus.UNAVAILABLE);
    }

    private static void assertReceiptFailure(AuctionPurchaseReceiptKind kind, AuctionPurchaseStatus expected) {
        AuctionPurchaseWorkflow workflow = new AuctionPurchaseWorkflow();
        List<AuctionPurchaseEffect> effects = new ArrayList<>();
        var host = (dev.donut.addon.expansion.automation.core.AuctionPurchaseHost)
            (frame, effect) -> { effects.add(effect); return true; };
        assertTrue(workflow.start(new AuctionPurchaseRequest("golden_apple", 1, new BigDecimal("100"))));
        workflow.tick(frame(0, null, null, null, 1, 0, null, null), host);
        workflow.tick(auctionFrame(1, 12, 10, appleListing(1, "$10"), 1, null, null), host);
        workflow.tick(auctionFrame(2, 12, 10, appleListing(1, "$10"), 1, null, null), host);
        workflow.tick(auctionFrame(4, 12, 10, appleListing(1, "$10"), 1, null, null), host);
        AuctionPurchaseEffect.ClickListing click = effects.stream()
            .filter(AuctionPurchaseEffect.ClickListing.class::isInstance)
            .map(AuctionPurchaseEffect.ClickListing.class::cast)
            .findFirst().orElseThrow();
        AuctionPurchaseReceipt receipt = new AuctionPurchaseReceipt(1L, SESSION, click.getAttemptId(), kind,
            "", null, null, null);
        AuctionPurchaseProgress result = workflow.tick(withReceipt(
            auctionFrame(5, 12, 11, appleListing(1, "$10"), 1, null, null), receipt), host);
        assertEquals(expected, result.getStatus());
        assertEquals(BigDecimal.ZERO, result.getReservedSpend());
    }

    @Test
    void pageNavigationWaitsForANewFullContentEpoch() {
        AuctionPurchaseWorkflow workflow = new AuctionPurchaseWorkflow();
        List<AuctionPurchaseEffect> effects = new ArrayList<>();
        var host = (dev.donut.addon.expansion.automation.core.AuctionPurchaseHost)
            (frame, effect) -> { effects.add(effect); return true; };
        assertTrue(workflow.start(new AuctionPurchaseRequest("golden_apple", 1, new BigDecimal("1000"))));
        workflow.tick(frame(0, null, null, null, 1, 0, null, null), host);

        SlotSnapshot nextSlot = new SlotSnapshot(53, "minecraft:arrow", 1, "Next Page", List.of());
        AuctionPurchaseFrame first = auctionFrame(1, 12, 10,
            List.of(nextSlot), 1, null, new dev.donut.addon.expansion.automation.core.AuctionPurchasePageAction(12, 50L, 53, nextSlot.getFingerprint()));
        workflow.tick(first, host);
        workflow.tick(auctionFrame(2, 12, 10, List.of(nextSlot), 1, null,
            new dev.donut.addon.expansion.automation.core.AuctionPurchasePageAction(12, 50L, 53, nextSlot.getFingerprint())), host);
        workflow.tick(auctionFrame(4, 12, 10, List.of(nextSlot), 1, null,
            new dev.donut.addon.expansion.automation.core.AuctionPurchasePageAction(12, 50L, 53, nextSlot.getFingerprint())), host);
        assertTrue(effects.stream().anyMatch(AuctionPurchaseEffect.NextPage.class::isInstance));

        AuctionPurchaseFrame nextPage = auctionFrame(5, 12, 11, appleListing(1, "$10"), 2, null, null);
        workflow.tick(nextPage, host);
        assertEquals(AuctionPurchaseStatus.RUNNING, workflow.progress().getStatus());
        workflow.tick(auctionFrame(6, 12, 11, appleListing(1, "$10"), 2, null, null), host);
        workflow.tick(auctionFrame(8, 12, 11, appleListing(1, "$10"), 2, null, null), host);
        assertTrue(effects.stream().anyMatch(AuctionPurchaseEffect.ClickListing.class::isInstance));
    }

    @Test
    void ordinaryPurchaseRejectsAugmentedBlockDataAndUnmatchedComponentsDoNotAck() {
        assertFalse(AuctionPurchaseListingRules.ordinary(
            new SlotSnapshot(0, "minecraft:chest", 1, "Chest", List.of(), "minecraft:block_entity_data={} ")));
        assertFalse(AuctionPurchaseListingRules.ordinary(
            new SlotSnapshot(0, "minecraft:stone", 1, "Stone", List.of(), "minecraft:block_state={axis=x}")));
    }

    @Test
    void inventoryAckUsesCapturedActiveMenuMapAndExactComponents() {
        AuctionPurchaseFrame before = auctionFrame(4, 12, 10, appleListing(64, "$640"), 1, null, null);
        InventoryIncreaseAckTracker tracker = new InventoryIncreaseAckTracker();
        assertTrue(tracker.begin(before, ITEM, Map.of(), COMPONENTS, "Golden Apple", List.of(), 64));
        assertEquals(InventoryAckStatus.PENDING, tracker.observe(sync(12, 11, 32, COMPONENTS), 1L));
        assertEquals(InventoryAckStatus.PENDING, tracker.observe(sync(12, 12, 64, "different-components"), 1L));
        assertEquals(InventoryAckStatus.PENDING, tracker.status(inventoryWith(ITEM, 64, COMPONENTS)));
        assertEquals(InventoryAckStatus.PENDING, tracker.observe(sync(12, 13, 64, COMPONENTS), 1L));
        assertEquals(InventoryAckStatus.ACKNOWLEDGED, tracker.status(inventoryWith(ITEM, 64, COMPONENTS)));
        assertEquals(64, tracker.confirmedIncrease(inventoryWith(ITEM, 64, COMPONENTS)));
        assertNull(tracker.confirmedIncrease(inventoryWith(ITEM, 63, COMPONENTS)));
    }

    @Test
    void inventoryAckIgnoresArmorAndOffhandMappingsOutsideStorageCapacity() {
        AuctionPurchaseFrame basic = auctionFrame(4, 12, 10, appleListing(1, "$10"), 1, null, null);
        AuctionPurchaseFrame withNonStorageMappings = new AuctionPurchaseFrame(basic.getSnapshot(), basic.getPlayerName(),
            basic.getAcknowledgedMenuId(), Map.of(54, 9, 55, 45, 56, 5), basic.getMenuSlotMapEpoch(),
            basic.getMenuContentEpoch(), basic.getContainerSlotCount(), basic.getNextPageAction(), basic.getConfirmation());
        InventoryIncreaseAckTracker tracker = new InventoryIncreaseAckTracker();
        assertTrue(tracker.begin(withNonStorageMappings, ITEM, Map.of(), COMPONENTS, "Golden Apple", List.of(), 1));
        assertEquals(InventoryAckStatus.PENDING, tracker.observe(sync(12, 11, 1, COMPONENTS), 1L));
        assertEquals(InventoryAckStatus.ACKNOWLEDGED, tracker.status(inventoryWith(ITEM, 1, COMPONENTS)));
        assertEquals(1, tracker.confirmedIncrease(inventoryWith(ITEM, 1, COMPONENTS)));
    }

    @Test
    void inventoryAckWaitsForTheExactServerDeltaToMatchTheLocalIncrease() {
        AuctionPurchaseFrame before = auctionFrame(4, 12, 10, appleListing(1, "$10"), 1, null, null);
        InventoryIncreaseAckTracker tracker = new InventoryIncreaseAckTracker();
        assertTrue(tracker.begin(before, ITEM, Map.of(), COMPONENTS, "Golden Apple", List.of(), 1));

        assertEquals(InventoryAckStatus.PENDING, tracker.observe(sync(12, 11, 1, COMPONENTS), 1L));
        assertEquals(InventoryAckStatus.PENDING, tracker.status(inventoryWith(ITEM, 12, COMPONENTS)));
        assertNull(tracker.confirmedIncrease(inventoryWith(ITEM, 12, COMPONENTS)));

        assertEquals(InventoryAckStatus.PENDING, tracker.observe(sync(12, 12, 12, COMPONENTS), 1L));
        assertEquals(InventoryAckStatus.ACKNOWLEDGED, tracker.status(inventoryWith(ITEM, 12, COMPONENTS)));
        assertEquals(12, tracker.confirmedIncrease(inventoryWith(ITEM, 12, COMPONENTS)));
    }

    @Test
    void compatiblePartialStackCanReceiveWholeListingWithoutAnEmptySlot() {
        AuctionPurchaseWorkflow workflow = new AuctionPurchaseWorkflow();
        List<AuctionPurchaseEffect> effects = new ArrayList<>();
        var host = (dev.donut.addon.expansion.automation.core.AuctionPurchaseHost)
            (frame, effect) -> { effects.add(effect); return true; };
        assertTrue(workflow.start(new AuctionPurchaseRequest("golden_apple", 2, new BigDecimal("100"))));
        workflow.tick(frame(0, null, null, null, 1, 0, null, null), host);
        MenuSnapshot inventory = fullInventoryWithPartialApple(62, COMPONENTS);
        List<SlotSnapshot> listing = appleListing(2, "$10");
        workflow.tick(auctionFrameWithInventory(1, 12, 10, listing, 1, inventory), host);
        workflow.tick(auctionFrameWithInventory(2, 12, 10, listing, 1, inventory), host);
        workflow.tick(auctionFrameWithInventory(4, 12, 10, listing, 1, inventory), host);

        assertTrue(effects.stream().anyMatch(AuctionPurchaseEffect.ClickListing.class::isInstance));
    }

    @Test
    void partialStackWithDifferentComponentsDoesNotCountAsPurchaseCapacity() {
        AuctionPurchaseWorkflow workflow = new AuctionPurchaseWorkflow();
        List<AuctionPurchaseEffect> effects = new ArrayList<>();
        var host = (dev.donut.addon.expansion.automation.core.AuctionPurchaseHost)
            (frame, effect) -> { effects.add(effect); return true; };
        assertTrue(workflow.start(new AuctionPurchaseRequest("golden_apple", 2, new BigDecimal("100"))));
        workflow.tick(frame(0, null, null, null, 1, 0, null, null), host);
        MenuSnapshot inventory = fullInventoryWithPartialApple(62, "different-components");
        List<SlotSnapshot> listing = appleListing(2, "$10");
        workflow.tick(auctionFrameWithInventory(1, 12, 10, listing, 1, inventory), host);
        workflow.tick(auctionFrameWithInventory(2, 12, 10, listing, 1, inventory), host);
        AuctionPurchaseProgress result = workflow.tick(
            auctionFrameWithInventory(4, 12, 10, listing, 1, inventory), host);

        assertEquals(AuctionPurchaseStatus.FAILED, result.getStatus());
        assertFalse(effects.stream().anyMatch(AuctionPurchaseEffect.ClickListing.class::isInstance));
    }

    @Test
    void nativeListingLoreIsNormalizedSeparatelyFromRealItemComponents() {
        InventorySlotValue listed = stackWithLore(List.of("Freshness lore", "Price: $640", "Seller: AnotherPlayer", "Ends in 1h"));
        InventorySlotValue delivered = stackWithLore(List.of("Freshness lore"));
        assertFalse(listed.getComponentFingerprint().equals(delivered.getComponentFingerprint()));
        assertFalse(listed.equals(delivered));
        assertEquals(listed.getProcurementFingerprint(), delivered.getProcurementFingerprint());
        assertEquals(List.of("Freshness lore"),
            dev.donut.addon.expansion.automation.core.AuctionPurchaseListingRules.procurementLore(listed.getLore()));

        SlotSnapshot candidate = new SlotSnapshot(0, ITEM, 64, listed.getName(), listed.getLore(),
            listed.getComponentFingerprint(), listed.getActualEnchantments(), 64, listed.getProcurementFingerprint());
        AuctionPurchaseFrame before = auctionFrame(4, 12, 10, List.of(candidate), 1, null, null);
        InventoryIncreaseAckTracker tracker = new InventoryIncreaseAckTracker();
        assertTrue(tracker.begin(before, ITEM, listed.getActualEnchantments(), listed.getProcurementFingerprint(),
            listed.getName(), dev.donut.addon.expansion.automation.core.AuctionPurchaseListingRules.procurementLore(listed.getLore()), 64));
        tracker.observe(new InventorySyncFrame(SESSION.getConnection(), 12, 11,
            Map.of(54, delivered), null, false), 1L);
        assertEquals(InventoryAckStatus.ACKNOWLEDGED, tracker.status(inventoryWith(delivered, 15)));
    }

    @Test
    void nativeLoreProcurementMetadataDoesNotBreakLegacyTransferEqualityOrAck() {
        ItemStack stack = new ItemStack(Items.GOLDEN_APPLE, 1);
        stack.set(DataComponents.LORE,
            new ItemLore(List.of((Component) Component.literal("Server-added listing detail"))));
        InventorySlotValue nativeSource = InventorySlotValue.fromStack(stack);
        InventorySlotValue legacySource = new InventorySlotValue(nativeSource.getItemId(), nativeSource.getCount(),
            nativeSource.getName(), nativeSource.getLore(), nativeSource.getComponentFingerprint(),
            nativeSource.getActualEnchantments());
        assertEquals(nativeSource, legacySource);
        assertEquals(nativeSource.hashCode(), legacySource.hashCode());

        InventorySlotValue empty = new InventorySlotValue("", 0);
        var machine = new InventoryTransferMachine();
        assertTrue(machine.begin(new InventoryStagePlan.Transfer(9, 36, nativeSource.getFingerprint(),
            empty.getFingerprint(), nativeSource, empty, 1, 1)));
        InventoryPickupAction take = machine.nextAction();
        assertTrue(machine.actionIssued(take));
        assertEquals(InventoryTransferProgress.WAITING, machine.acknowledge(InventoryAckStatus.ACKNOWLEDGED,
            Map.of(9, empty, 36, empty), legacySource));

        InventoryPickupAction place = machine.nextAction();
        assertTrue(machine.actionIssued(place));
        InventorySlotValue legacyDelivered = new InventorySlotValue(nativeSource.getItemId(), 1,
            nativeSource.getName(), nativeSource.getLore(), nativeSource.getComponentFingerprint(),
            nativeSource.getActualEnchantments());
        assertEquals(InventoryTransferProgress.READY, machine.acknowledge(InventoryAckStatus.ACKNOWLEDGED,
            Map.of(9, empty, 36, legacyDelivered), empty));
    }

    private static List<SlotSnapshot> appleListing(int count, String price) {
        return List.of(new SlotSnapshot(0, ITEM, count, "Golden Apple",
            List.of("Price: " + price, "Seller: AnotherPlayer"), COMPONENTS, Map.of(), 64));
    }

    private static AuctionPurchaseFrame auctionFrame(long tick, int menuId, int stateId,
            List<SlotSnapshot> slots, long contentEpoch, MenuSnapshot inventory,
            dev.donut.addon.expansion.automation.core.AuctionPurchasePageAction page) {
        MenuSnapshot menu = new MenuSnapshot(50L, menuId, "Auction House", slots, true, stateId);
        return frame(tick, menu, inventory == null ? emptyInventory(stateId + 100) : inventory, null,
            1, contentEpoch, page, null);
    }

    private static AuctionPurchaseFrame auctionFrameWithInventory(long tick, int menuId, int stateId,
            List<SlotSnapshot> slots, long contentEpoch, MenuSnapshot inventory) {
        MenuSnapshot menu = new MenuSnapshot(50L, menuId, "Auction House", slots, true, stateId);
        AutomationSnapshot snapshot = new AutomationSnapshot(SESSION, tick, menu, Set.of(), Set.of(), "", null, inventory);
        return new AuctionPurchaseFrame(snapshot, "Buyer", menuId, Map.of(54, 9), 1L, contentEpoch, 54, null, null);
    }

    private static AuctionPurchaseFrame frame(long tick, MenuSnapshot menu, MenuSnapshot inventory,
            DialogSnapshot dialog, long mapEpoch, long contentEpoch,
            dev.donut.addon.expansion.automation.core.AuctionPurchasePageAction page,
            AuctionPurchaseConfirmation confirmation) {
        AutomationSnapshot snapshot = new AutomationSnapshot(SESSION, tick, menu, Set.of(), Set.of(), "", dialog, inventory);
        return new AuctionPurchaseFrame(snapshot, "Buyer", menu == null ? null : menu.getMenuId(),
            Map.of(54, 9), mapEpoch, contentEpoch, 54, page, confirmation);
    }

    private static AuctionPurchaseFrame withReceipt(AuctionPurchaseFrame frame, AuctionPurchaseReceipt... receipts) {
        return new AuctionPurchaseFrame(frame.getSnapshot(), frame.getPlayerName(), frame.getAcknowledgedMenuId(),
            frame.getActiveInventorySlotMap(), frame.getMenuSlotMapEpoch(), frame.getMenuContentEpoch(),
            frame.getContainerSlotCount(), frame.getNextPageAction(), frame.getConfirmation(), List.of(receipts));
    }

    private static MenuSnapshot emptyInventory(int stateId) {
        return inventoryWith("", 0, "", stateId);
    }

    private static MenuSnapshot inventoryWith(String itemId, int count, String components) {
        return inventoryWith(itemId, count, components, 5);
    }

    private static MenuSnapshot inventoryWith(String itemId, int count, String components, int stateId) {
        List<SlotSnapshot> slots = new ArrayList<>();
        for (int index = 9; index <= 44; index++) {
            if (index == 9 && count > 0) {
                slots.add(new SlotSnapshot(index, itemId, count, "Golden Apple", List.of(), components, Map.of(), 64));
            } else {
                slots.add(new SlotSnapshot(index, "", 0, "", List.of(), "", Map.of(), 64));
            }
        }
        return new MenuSnapshot(60L, 0, "Player Inventory", slots, true, stateId);
    }

    private static MenuSnapshot fullInventoryWithPartialApple(int appleCount, String components) {
        List<SlotSnapshot> slots = new ArrayList<>();
        for (int index = 9; index <= 44; index++) {
            if (index == 9) {
                slots.add(new SlotSnapshot(index, ITEM, appleCount, "Golden Apple", List.of(), components, Map.of(), 64));
            } else {
                slots.add(new SlotSnapshot(index, "minecraft:stone", 64, "Stone", List.of(), "stone-components", Map.of(), 64));
            }
        }
        return new MenuSnapshot(60L, 0, "Player Inventory", slots, true, 5);
    }

    private static InventorySyncFrame sync(int menuId, int stateId, int count, String components) {
        return new InventorySyncFrame(SESSION.getConnection(), menuId, stateId,
            Map.of(54, new InventorySlotValue(ITEM, count, "Golden Apple", List.of(), components, Map.of())),
            null, false);
    }

    private static InventorySlotValue stackWithLore(List<String> lore) {
        ItemStack stack = new ItemStack(Items.GOLDEN_APPLE, 64);
        stack.set(DataComponents.LORE, new ItemLore(lore.stream().map(line -> (Component) Component.literal(line)).toList()));
        return InventorySlotValue.fromStack(stack);
    }

    private static MenuSnapshot inventoryWith(InventorySlotValue value, int stateId) {
        List<SlotSnapshot> slots = new ArrayList<>();
        for (int index = 9; index <= 44; index++) {
            if (index == 9) {
                slots.add(new SlotSnapshot(index, value.getItemId(), value.getCount(), value.getName(), value.getLore(),
                    value.getComponentFingerprint(), value.getActualEnchantments(), 64, value.getProcurementFingerprint()));
            } else {
                slots.add(new SlotSnapshot(index, "", 0, "", List.of(), "", Map.of(), 64));
            }
        }
        return new MenuSnapshot(60L, 0, "Player Inventory", slots, true, stateId);
    }
}
