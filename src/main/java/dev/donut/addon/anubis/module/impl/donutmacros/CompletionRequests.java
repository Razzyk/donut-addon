// Ported from the supplied Anubis macro extraction; original classes were only read statically.
package dev.donut.addon.anubis.module.impl.donutmacros;

import java.util.List;
import dev.donut.addon.anubis.util.CompletionChannel;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

@Environment(value=EnvType.CLIENT)
final class CompletionRequests {
    private CompletionChannel.Form form = CompletionChannel.Form.BOTH;

    CompletionRequests() {
    }

    CompletionChannel.Form form() {
        return this.form;
    }

    void form(CompletionChannel.Form form) {
        this.form = form;
    }

    int fire(String partial) {
        return this.fire(partial, this.form);
    }

    int fire(String partial, CompletionChannel.Form using) {
        return CompletionChannel.INSTANCE.fire((Object)this, partial, using);
    }

    List<String> poll(int id) {
        return CompletionChannel.INSTANCE.poll(id);
    }

    void forget(int id) {
        CompletionChannel.INSTANCE.forget(id);
    }

    void prune(long now) {
        CompletionChannel.INSTANCE.prune(now);
    }

    void clear() {
        CompletionChannel.INSTANCE.clear((Object)this);
        this.form = CompletionChannel.Form.BOTH;
    }
}
