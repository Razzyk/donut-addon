package dev.donut.addon.expansion.automation.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DonutSellWorkflowTest {
    private static final SessionStamp SESSION = new SessionStamp(3L, 9L, 12L, 14L);
    private static final InventorySlotValue STONE = stack("minecraft:stone", 12, "Stone", "stone-components");
    private static final InventorySlotValue EMPTY = new InventorySlotValue("", 0);
    private static final Map<Integer, InventorySlotValue> EMPTY_SELL = Map.of(0, EMPTY, 1, EMPTY);

    @Test
    void offersOnlyApprovedStackAndCountsServerAcknowledgedSale() {
        var workflow = workflow(1L, STONE);
        var open = assertInstanceOf(DonutSellEffect.OpenSell.class, workflow.tick(frame(100, null, 0, false, true, EMPTY_SELL)));
        assertEquals(DonutSellStatus.WAITING_MENU, workflow.reportDispatch(open.getId(), true, 100));

        var opened = frame(200, sellMenu(5, STONE, EMPTY_SELL), 8, true, true, EMPTY_SELL);
        assertNull(workflow.tick(opened));
        assertEquals(DonutSellStatus.OFFERING, workflow.status());
        var offer = assertInstanceOf(DonutSellEffect.OfferStack.class, workflow.tick(opened));
        assertEquals(0, offer.getInventorySlot());
        assertEquals(54, offer.getMenuSlot());
        workflow.reportDispatch(offer.getId(), true, 210);

        var staged = Map.of(0, STONE, 1, EMPTY);
        assertEquals(DonutSellStatus.OFFERING, workflow.acknowledgeOffer(new DonutSellOfferAck(
                offer.getId(), SESSION, 7, 100, 8, 6, 220, 0, STONE, EMPTY,
                EMPTY_SELL, staged, true, true)));

        var stagedMenu = sellMenu(6, EMPTY, staged);
        assertNull(workflow.tick(frame(230, stagedMenu, 8, true, true, staged)));
        var confirm = assertInstanceOf(DonutSellEffect.ConfirmSale.class,
                workflow.tick(frame(231, stagedMenu, 8, true, true, staged)));
        assertEquals(53, confirm.getSlot());
        workflow.reportDispatch(confirm.getId(), true, 240);

        assertEquals(DonutSellStatus.COMPLETE, workflow.acknowledgeSale(new DonutSellResultAck(
                confirm.getId(), SESSION, 7, 100, 8, 7, 250, staged, EMPTY_SELL, true, true), 250));
        assertEquals(Map.of(DonutSellIdentity.of(STONE), 12), workflow.totals().getSold());
        assertEquals(Map.of(), workflow.totals().getRemaining());
        assertTrue(workflow.start(request(1L, STONE), 300));
        var secondCycle = assertInstanceOf(DonutSellEffect.OpenSell.class,
                workflow.tick(frame(300, null, 0, false, true, EMPTY_SELL)));
        assertTrue(secondCycle.getId() > confirm.getId());
    }

    @Test
    void serverConfirmedOpenSellRateLimitSchedulesANewCommandAfterTheHostDelay() {
        var workflow = workflow(30L, STONE);
        var first = assertInstanceOf(DonutSellEffect.OpenSell.class,
                workflow.tick(frame(100, null, 0, false, true, EMPTY_SELL)));
        workflow.reportDispatch(first.getId(), true, 110);

        assertEquals(DonutSellStatus.IDLE, workflow.rejectOpenCommand(first.getId(), 120, 500));
        assertNull(workflow.tick(frame(499, null, 0, false, true, EMPTY_SELL)));
        var retry = assertInstanceOf(DonutSellEffect.OpenSell.class,
                workflow.tick(frame(500, null, 0, false, true, EMPTY_SELL)));
        assertTrue(retry.getId() > first.getId());
    }

    @Test
    void permitsAHostVerifiedOwnedSellContainerWithAnUnrecognizedTitle() {
        var workflow = workflow(32L, STONE);
        var open = assertInstanceOf(DonutSellEffect.OpenSell.class,
                workflow.tick(frame(100, null, 0, false, true, EMPTY_SELL)));
        workflow.reportDispatch(open.getId(), true, 110);

        var sourceShapedMenu = new MenuSnapshot(100L, 7, "Items", sellMenu(5, STONE, EMPTY_SELL).getSlots(), true, 5);
        assertNull(workflow.tick(frame(120, sourceShapedMenu, 8, true, true, EMPTY_SELL, true)));
        assertEquals(DonutSellStatus.OFFERING, workflow.status());
        assertInstanceOf(DonutSellEffect.OfferStack.class,
                workflow.tick(frame(121, sourceShapedMenu, 8, true, true, EMPTY_SELL, true)));
    }

    @Test
    void unrecognizedSellMenuStillRequiresExplicitHostOwnershipProof() {
        var workflow = workflow(33L, STONE);
        var open = assertInstanceOf(DonutSellEffect.OpenSell.class,
                workflow.tick(frame(100, null, 0, false, true, EMPTY_SELL)));
        workflow.reportDispatch(open.getId(), true, 110);

        var unrelatedMenu = new MenuSnapshot(100L, 7, "Items", sellMenu(5, STONE, EMPTY_SELL).getSlots(), true, 5);
        assertNull(workflow.tick(frame(120, unrelatedMenu, 8, true, true, EMPTY_SELL)));
        assertEquals(DonutSellStatus.FAILED, workflow.status());
    }

    @Test
    void openSellRateLimitCannotResetUnsentStaleOrLaterWorkflowEffects() {
        var workflow = workflow(31L, STONE);
        var open = assertInstanceOf(DonutSellEffect.OpenSell.class,
                workflow.tick(frame(100, null, 0, false, true, EMPTY_SELL)));
        assertEquals(DonutSellStatus.IDLE, workflow.rejectOpenCommand(open.getId(), 101, 200));
        assertSame(open, workflow.pendingEffect());
        workflow.reportDispatch(open.getId(), true, 110);
        assertEquals(DonutSellStatus.WAITING_MENU, workflow.rejectOpenCommand(open.getId() + 1, 120, 500));
        assertEquals(DonutSellStatus.WAITING_MENU, workflow.rejectOpenCommand(open.getId(), 100, 500));
        assertEquals(DonutSellStatus.WAITING_MENU, workflow.rejectOpenCommand(open.getId(), 120, 119));
        assertEquals(DonutSellStatus.IDLE, workflow.rejectOpenCommand(open.getId(), 120, 500));
        var next = assertInstanceOf(DonutSellEffect.OpenSell.class,
                workflow.tick(frame(500, null, 0, false, true, EMPTY_SELL)));
        workflow.reportDispatch(next.getId(), true, 510);
        var menu = frame(520, sellMenu(5, STONE, EMPTY_SELL), 8, true, true, EMPTY_SELL);
        assertNull(workflow.tick(menu));
        var offer = assertInstanceOf(DonutSellEffect.OfferStack.class, workflow.tick(menu));
        assertEquals(DonutSellStatus.OFFERING, workflow.reportDispatch(offer.getId(), false, 521));
        assertEquals(DonutSellStatus.OFFERING, workflow.rejectOpenCommand(next.getId(), 522, 600));
    }

    @Test
    void rejectsAQuickMoveWithoutAnExactMatchingSellAreaIncrease() {
        var workflow = workflow(1L, STONE);
        var effect = beginAndOffer(workflow, 100, 5);
        var noContainerChange = Map.of(0, EMPTY, 1, EMPTY);
        assertEquals(DonutSellStatus.UNCERTAIN, workflow.acknowledgeOffer(new DonutSellOfferAck(
                effect.getId(), SESSION, 7, 100, 8, 6, 220, 0, STONE, EMPTY,
                EMPTY_SELL, noContainerChange, true, true)));
        assertNull(workflow.tick(frame(230, sellMenu(6, EMPTY, noContainerChange), 8, true, true, noContainerChange)));
    }

    @Test
    void rejectsOfferEvidenceFromAChangedSlotMapEpoch() {
        var workflow = workflow(1L, STONE);
        var effect = beginAndOffer(workflow, 100, 5);
        var staged = Map.of(0, STONE, 1, EMPTY);
        assertEquals(DonutSellStatus.UNCERTAIN, workflow.acknowledgeOffer(new DonutSellOfferAck(
                effect.getId(), SESSION, 7, 100, 9, 6, 220, 0, STONE, EMPTY,
                EMPTY_SELL, staged, true, true)));
    }

    @Test
    void saleTimeoutIsTerminalAndCannotBeResetWithinSameBuildRun() {
        var workflow = workflow(11L, STONE);
        var confirm = reachConfirmation(workflow, 11L);
        workflow.reportDispatch(confirm.getId(), true, 1_000);
        assertNull(workflow.tick(frame(6_000_001_001L, sellMenu(6, EMPTY, Map.of(0, STONE, 1, EMPTY)),
                8, true, true, Map.of(0, STONE, 1, EMPTY))));
        assertEquals(DonutSellStatus.UNCERTAIN, workflow.status());
        assertFalse(workflow.start(request(11L, STONE), 7_000_000_000L));
        assertNull(workflow.tick(frame(7_000_000_001L, null, 0, false, false, EMPTY_SELL)));
    }

    @Test
    void aSaleAckCannotIntroduceStacksOrIncreaseRemainingQuantity() {
        var workflow = workflow(12L, STONE);
        var confirm = reachConfirmation(workflow, 12L);
        var staged = Map.of(0, STONE, 1, EMPTY);
        workflow.reportDispatch(confirm.getId(), true, 1_000);
        var wrong = Map.of(0, stack("minecraft:gold_ingot", 12, "Gold Ingot", "gold-components"), 1, EMPTY);
        assertEquals(DonutSellStatus.UNCERTAIN, workflow.acknowledgeSale(new DonutSellResultAck(
                confirm.getId(), SESSION, 7, 100, 8, 7, 1_100, staged, wrong, true, true), 1_100));
    }

    @Test
    void sellIdentityKeepsNamesComponentsAndTextBoundariesDistinct() {
        var named = stack("minecraft:stone", 1, "Blue # Stone", "components-a");
        var anotherName = stack("minecraft:stone", 1, "Blue", "components-a");
        var anotherComponent = stack("minecraft:stone", 1, "Blue # Stone", "components-b");
        var boundaryA = new InventorySlotValue("minecraft:stone", 1, "a#b", List.of("c"), "d", Map.of(), "d");
        var boundaryB = new InventorySlotValue("minecraft:stone", 1, "a", List.of("b#c"), "d", Map.of(), "d");

        assertNotEquals(DonutSellIdentity.of(named), DonutSellIdentity.of(anotherName));
        assertNotEquals(DonutSellIdentity.of(named), DonutSellIdentity.of(anotherComponent));
        assertNotEquals(DonutSellIdentity.of(boundaryA), DonutSellIdentity.of(boundaryB));
    }

    @Test
    void itemTypeGridModeAllowsServerDecorationWithoutWeakeningCandidateOwnership() {
        var request = new DonutSellRequest(21L, SESSION, List.of(new DonutSellCandidate(0, STONE)),
                java.util.Set.of(0, 1), 53, DonutSellGridIdentityMode.ITEM_TYPE_ONLY);
        var workflow = new DonutSellWorkflow();
        assertTrue(workflow.start(request, 100));
        var open = assertInstanceOf(DonutSellEffect.OpenSell.class,
                workflow.tick(frame(100, null, 0, false, true, EMPTY_SELL)));
        workflow.reportDispatch(open.getId(), true, 100);
        var menu = frame(200, sellMenu(5, STONE, EMPTY_SELL), 8, true, true, EMPTY_SELL);
        assertNull(workflow.tick(menu));
        var offer = assertInstanceOf(DonutSellEffect.OfferStack.class, workflow.tick(menu));
        workflow.reportDispatch(offer.getId(), true, 210);

        var decorated = new InventorySlotValue("minecraft:stone", 12, "Market Stone",
                List.of("Server price text"), "server-decoration", Map.of(), "server-decoration");
        var decoratedContents = Map.of(0, decorated, 1, EMPTY);
        assertEquals(DonutSellStatus.OFFERING, workflow.acknowledgeOffer(new DonutSellOfferAck(
                offer.getId(), SESSION, 7, 100, 8, 6, 220, 0, STONE, EMPTY,
                EMPTY_SELL, decoratedContents, true, true)));
        assertEquals(Map.of(DonutSellIdentity.of(STONE), 12), workflow.totals().getRemaining());

        var stagedMenu = sellMenu(6, EMPTY, decoratedContents);
        assertNull(workflow.tick(frame(230, stagedMenu, 8, true, true, decoratedContents)));
        var confirm = assertInstanceOf(DonutSellEffect.ConfirmSale.class,
                workflow.tick(frame(231, stagedMenu, 8, true, true, decoratedContents)));
        workflow.reportDispatch(confirm.getId(), true, 240);
        assertEquals(DonutSellStatus.COMPLETE, workflow.acknowledgeSale(new DonutSellResultAck(
                confirm.getId(), SESSION, 7, 100, 8, 7, 250, decoratedContents, EMPTY_SELL, true, true), 250));
        assertEquals(Map.of(DonutSellIdentity.of(STONE), 12), workflow.totals().getSold());
        assertEquals(Map.of(), workflow.totals().getRemaining());
    }

    @Test
    void itemTypeGridModeRejectsAmbiguousDistinctVariants() {
        var request = new DonutSellRequest(22L, SESSION, List.of(
                new DonutSellCandidate(0, STONE),
                new DonutSellCandidate(1, stack("minecraft:stone", 4, "Named Stone", "named-components"))),
                java.util.Set.of(0, 1), 53, DonutSellGridIdentityMode.ITEM_TYPE_ONLY);
        assertFalse(new DonutSellWorkflow().start(request, 100));
    }

    @Test
    void itemTypeGridModeStillRequiresExactCandidateInventoryStack() {
        var request = new DonutSellRequest(23L, SESSION, List.of(new DonutSellCandidate(0, STONE)),
                java.util.Set.of(0, 1), 53, DonutSellGridIdentityMode.ITEM_TYPE_ONLY);
        var workflow = new DonutSellWorkflow();
        assertTrue(workflow.start(request, 100));
        var offer = beginAndOffer(workflow, 100, 5);
        var otherNamedStone = stack("minecraft:stone", 12, "Other Stone", "other-components");
        var after = Map.of(0, stack("minecraft:stone", 12, "Market Stone", "server-decoration"), 1, EMPTY);
        assertEquals(DonutSellStatus.UNCERTAIN, workflow.acknowledgeOffer(new DonutSellOfferAck(
                offer.getId(), SESSION, 7, 100, 8, 6, 220, 0, otherNamedStone, EMPTY,
                EMPTY_SELL, after, true, true)));
    }

    @Test
    void makesAtMostThreePacedConfirmationClicksAndReportsRemainder() {
        var workflow = workflow(13L, STONE);
        var confirm = reachConfirmation(workflow, 13L);
        var staged = Map.of(0, STONE, 1, EMPTY);
        long now = 1_000;
        for (int click = 1; click <= 3; click++) {
            workflow.reportDispatch(confirm.getId(), true, now);
            var status = workflow.acknowledgeSale(new DonutSellResultAck(
                    confirm.getId(), SESSION, 7, 100, 8, 6 + click, now + 1, staged, staged, true, true), now + 1);
            if (click < 3) {
                assertEquals(DonutSellStatus.READY_TO_CONFIRM, status);
                now += click == 1 ? 250_000_001L : 900_000_001L;
                confirm = assertInstanceOf(DonutSellEffect.ConfirmSale.class,
                        workflow.tick(frame(now, sellMenu(6 + click, EMPTY, staged), 8, true, true, staged)));
            } else {
                assertEquals(DonutSellStatus.PARTIAL, status);
            }
        }
        assertEquals(Map.of(), workflow.totals().getSold());
        assertEquals(Map.of(DonutSellIdentity.of(STONE), 12), workflow.totals().getRemaining());
        assertNull(workflow.pendingEffect());
        assertFalse(workflow.start(request(13L, STONE), now + 1_000_000_000L));
        var inventoryBeforeClose = playerInventory(EMPTY);
        var incompleteReturn = playerInventory(stack("minecraft:stone", 11, "Stone", "stone-components"));
        assertEquals(DonutSellStatus.PARTIAL, workflow.acknowledgePartialReturn(partialReturnAck(
                13L, now, inventoryBeforeClose, incompleteReturn, 40, 40, 41, 55, 55)));
        assertFalse(workflow.start(request(13L, STONE), now + 3));
        var completeReturn = playerInventory(STONE);
        assertEquals(DonutSellStatus.PARTIAL, workflow.acknowledgePartialReturn(partialReturnAck(
                13L, now, inventoryBeforeClose, completeReturn, 40, 40, 41, 55, 55)));
        assertTrue(workflow.start(request(13L, STONE), now + 5));
        var nextCycle = assertInstanceOf(DonutSellEffect.OpenSell.class,
                workflow.tick(frame(now + 5, null, 0, false, true, EMPTY_SELL)));
        assertTrue(nextCycle.getId() > 0L);
    }

    @Test
    void partialReturnRequiresNewSameSessionInventoryPacketAfterMenuClose() {
        var workflow = workflow(14L, STONE);
        makePartial(workflow, 14L);
        var before = playerInventory(EMPTY);
        var after = playerInventory(STONE);
        var stale = new DonutSellPartialReturnAck(14L, SESSION, 7, 100, 8, 2_000_000_000L,
                1_800_000_000L, 1_900_000_000L, 1_950_000_000L, 70, 71, 71, 55, 55, 7, 0,
                before, after, true, true, true);
        assertEquals(DonutSellStatus.PARTIAL, workflow.acknowledgePartialReturn(stale));
        assertFalse(workflow.start(request(14L, STONE), 2_000_000_001L));
    }

    @Test
    void partialReturnRejectsAChangedPlayerInventoryMapEpoch() {
        var workflow = workflow(15L, STONE);
        makePartial(workflow, 15L);
        var ack = partialReturnAck(15L, 2_000_000_000L, playerInventory(EMPTY), playerInventory(STONE),
                80, 80, 81, 55, 56);
        assertEquals(DonutSellStatus.PARTIAL, workflow.acknowledgePartialReturn(ack));
        assertFalse(workflow.start(request(15L, STONE), 2_000_000_001L));
    }

    @Test
    void partialReturnUsesSeparatePlayerMenuEvidenceWithoutComparingSellStateIds() {
        var workflow = workflow(16L, STONE);
        makePartial(workflow, 16L);
        var ack = partialReturnAck(16L, 2_000_000_000L, playerInventory(EMPTY), playerInventory(STONE),
                90, 91, 92, 60, 60);
        assertEquals(DonutSellStatus.PARTIAL, workflow.acknowledgePartialReturn(ack));
        assertTrue(workflow.start(request(16L, STONE), 2_000_000_001L));
    }

    private static DonutSellWorkflow workflow(long runId, InventorySlotValue source) {
        var workflow = new DonutSellWorkflow();
        assertTrue(workflow.start(request(runId, source), 100));
        return workflow;
    }

    private static void makePartial(DonutSellWorkflow workflow, long runId) {
        var confirm = reachConfirmation(workflow, runId);
        var staged = Map.of(0, STONE, 1, EMPTY);
        long now = 1_000;
        for (int click = 1; click <= 3; click++) {
            workflow.reportDispatch(confirm.getId(), true, now);
            var status = workflow.acknowledgeSale(new DonutSellResultAck(
                    confirm.getId(), SESSION, 7, 100, 8, 6 + click, now + 1, staged, staged, true, true), now + 1);
            if (click < 3) {
                now += click == 1 ? 250_000_001L : 900_000_001L;
                confirm = assertInstanceOf(DonutSellEffect.ConfirmSale.class,
                        workflow.tick(frame(now, sellMenu(6 + click, EMPTY, staged), 8, true, true, staged)));
            } else {
                assertEquals(DonutSellStatus.PARTIAL, status);
            }
        }
    }

    private static DonutSellPartialReturnAck partialReturnAck(long runId, long now,
                                                               Map<Integer, InventorySlotValue> before,
                                                               Map<Integer, InventorySlotValue> after,
                                                               long baselineOrdinal, long closeOrdinal, long afterOrdinal,
                                                               long beforeEpoch, long afterEpoch) {
        return new DonutSellPartialReturnAck(runId, SESSION, 7, 100, 8, now + 5,
                now + 2, now + 3, now + 4, baselineOrdinal, closeOrdinal, afterOrdinal,
                beforeEpoch, afterEpoch, 7, 0, before, after, true, true, true);
    }

    private static Map<Integer, InventorySlotValue> playerInventory(InventorySlotValue slotNine) {
        var inventory = new HashMap<Integer, InventorySlotValue>();
        for (int slot = 0; slot < 36; slot++) inventory.put(slot, EMPTY);
        inventory.put(9, slotNine);
        return Map.copyOf(inventory);
    }

    private static DonutSellRequest request(long runId, InventorySlotValue source) {
        return new DonutSellRequest(runId, SESSION, List.of(new DonutSellCandidate(0, source)),
                java.util.Set.of(0, 1), 53);
    }

    private static DonutSellEffect.OfferStack beginAndOffer(DonutSellWorkflow workflow, long now, int stateId) {
        var open = assertInstanceOf(DonutSellEffect.OpenSell.class,
                workflow.tick(frame(now, null, 0, false, true, EMPTY_SELL)));
        workflow.reportDispatch(open.getId(), true, now);
        var menu = frame(now + 1, sellMenu(stateId, STONE, EMPTY_SELL), 8, true, true, EMPTY_SELL);
        assertNull(workflow.tick(menu));
        var offer = assertInstanceOf(DonutSellEffect.OfferStack.class, workflow.tick(menu));
        workflow.reportDispatch(offer.getId(), true, now + 2);
        return offer;
    }

    private static DonutSellEffect.ConfirmSale reachConfirmation(DonutSellWorkflow workflow, long runId) {
        var offer = beginAndOffer(workflow, 100, 5);
        var staged = Map.of(0, STONE, 1, EMPTY);
        workflow.acknowledgeOffer(new DonutSellOfferAck(offer.getId(), SESSION, 7, 100, 8, 6, 220,
                0, STONE, EMPTY, EMPTY_SELL, staged, true, true));
        var menu = sellMenu(6, EMPTY, staged);
        assertNull(workflow.tick(frame(230, menu, 8, true, true, staged)));
        return assertInstanceOf(DonutSellEffect.ConfirmSale.class,
                workflow.tick(frame(231, menu, 8, true, true, staged)));
    }

    private static DonutSellFrame frame(long now, MenuSnapshot menu, long epoch, boolean openAck, boolean canCommand,
                                        Map<Integer, InventorySlotValue> sellContents) {
        return frame(now, menu, epoch, openAck, canCommand, sellContents, false);
    }

    private static DonutSellFrame frame(long now, MenuSnapshot menu, long epoch, boolean openAck, boolean canCommand,
                                        Map<Integer, InventorySlotValue> sellContents, boolean hostVerified) {
        var playerSlots = new HashMap<Integer, Integer>();
        for (int i = 0; i < 36; i++) playerSlots.put(i, 54 + i);
        return new DonutSellFrame(SESSION, now, canCommand, menu, epoch, openAck,
                playerSlots, java.util.Set.of(0, 1), 53, hostVerified);
    }

    private static MenuSnapshot sellMenu(int stateId, InventorySlotValue playerSlot0,
                                         Map<Integer, InventorySlotValue> sellContents) {
        var slots = new ArrayList<SlotSnapshot>();
        for (int i = 0; i < 54; i++) {
            InventorySlotValue stack = sellContents.getOrDefault(i, EMPTY);
            if (i == 53) stack = stack("minecraft:lime_dye", 1, "Sell", "button-components");
            slots.add(slot(i, stack));
        }
        for (int i = 0; i < 36; i++) {
            InventorySlotValue stack = i == 0 ? playerSlot0 : EMPTY;
            slots.add(slot(54 + i, stack));
        }
        return new MenuSnapshot(100L, 7, "Sell Items", List.copyOf(slots), true, stateId);
    }

    private static SlotSnapshot slot(int index, InventorySlotValue stack) {
        return new SlotSnapshot(index, stack.getItemId(), stack.getCount(), stack.getName(), stack.getLore(),
                stack.getComponentFingerprint(), stack.getActualEnchantments(), 64, stack.getProcurementFingerprint());
    }

    private static InventorySlotValue stack(String id, int count, String name, String components) {
        return new InventorySlotValue(id, count, name, List.of(), components, Map.of(), components);
    }
}
