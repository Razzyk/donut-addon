package dev.donut.addon.expansion;

import dev.donut.addon.expansion.automation.*;
import java.util.List;

/** Exercises draft migration contracts without making the drafts public modules. */
final class MacroDrafts {
    static List<DonutAutomationModule> modules() {
        return List.of(new DonutAuctionWatch(), new DonutListings(), new DonutSellLoop(),
            new DonutTradeOrders(), new DonutOrderCollector(), new DonutTravelRequests(),
            new DonutVisitorGuard(), new DonutQuickTransfer(), new DonutCrystalChain(),
            new DonutAnchorChain(), new DonutChatKeys());
    }
}
