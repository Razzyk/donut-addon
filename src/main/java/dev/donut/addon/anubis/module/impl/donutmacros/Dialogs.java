// Ported from the supplied Anubis macro extraction; original classes were only read statically.
package dev.donut.addon.anubis.module.impl.donutmacros;

import dev.donut.addon.mixin.AccessorOrderDialogScreen;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import dev.donut.addon.anubis.module.impl.donutmacros.ServerText;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.server.dialog.ButtonListDialog;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.dialog.Input;
import net.minecraft.server.dialog.MultiActionDialog;
import net.minecraft.server.dialog.SimpleDialog;
import net.minecraft.server.dialog.body.DialogBody;
import net.minecraft.server.dialog.body.ItemBody;
import net.minecraft.server.dialog.body.PlainMessage;
import net.minecraft.server.dialog.input.BooleanInput;
import net.minecraft.server.dialog.input.InputControl;
import net.minecraft.server.dialog.input.TextInput;
import net.minecraft.client.gui.screens.dialog.DialogScreen;
import net.minecraft.server.dialog.ActionButton;
import net.minecraft.server.dialog.action.Action;
import net.minecraft.network.chat.contents.ObjectContents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.TextColor;

@Environment(value=EnvType.CLIENT)
final class Dialogs {
    private Dialogs() {
    }

    static View current(Minecraft mc) {
        Screen class_4372 = mc.gui.screen();
        if (!(class_4372 instanceof DialogScreen)) {
            return null;
        }
        DialogScreen screen = (DialogScreen)class_4372;
        Dialog dialog = ((AccessorOrderDialogScreen)screen).anubis$dialog();
        if (dialog == null) {
            return null;
        }
        ArrayList<ActionButton> raw = new ArrayList<>();
        if (dialog instanceof MultiActionDialog) {
            MultiActionDialog multi = (MultiActionDialog)dialog;
            raw.addAll(multi.actions());
        }
        if (dialog instanceof SimpleDialog) {
            SimpleDialog simple = (SimpleDialog)dialog;
            raw.addAll(simple.mainActions());
        }
        if (dialog instanceof ButtonListDialog) {
            ButtonListDialog list = (ButtonListDialog)dialog;
            list.exitAction().ifPresent(raw::add);
        }
        ArrayList<Button> buttons = new ArrayList<Button>(raw.size());
        for (ActionButton button : raw) {
            Component label = button.button().label();
            String text = Dialogs.visibleText(label);
            buttons.add(new Button(button, ServerText.normalize(text), ServerText.key(text), label));
        }
        ArrayList<String> body = new ArrayList<String>();
        ArrayList<ItemStack> items = new ArrayList<ItemStack>();
        for (DialogBody part : dialog.common().body()) {
            if (part instanceof PlainMessage) {
                PlainMessage message2 = (PlainMessage)part;
                body.add(ServerText.normalize(Dialogs.visibleText(message2.contents())));
            }
            if (!(part instanceof ItemBody)) continue;
            ItemBody item = (ItemBody)part;
            items.add(item.item().create());
            item.description().ifPresent(message -> body.add(ServerText.normalize(Dialogs.visibleText(message.contents()))));
        }
        ArrayList<String> textInputs = new ArrayList<String>();
        for (Input input : dialog.common().inputs()) {
            if (!(input.control() instanceof TextInput)) continue;
            textInputs.add(input.key());
        }
        return new View(dialog, ServerText.normalize(Dialogs.visibleText(dialog.common().title())), buttons, body, textInputs, items);
    }

    static String visibleText(Component component) {
        StringBuilder out = new StringBuilder();
        Dialogs.appendVisible(component, out);
        return out.toString();
    }

    private static void appendVisible(Component component, StringBuilder out) {
        if (!(component.getContents() instanceof ObjectContents)) {
            component.getContents().visit(text -> {
                out.append(text);
                return Optional.empty();
            });
        }
        for (Component sibling : component.getSiblings()) {
            Dialogs.appendVisible(sibling, out);
        }
    }

    static boolean press(Minecraft mc, View view, Button button, Map<String, String> text) {
        Screen class_4372 = mc.gui.screen();
        if (!(class_4372 instanceof DialogScreen)) {
            return false;
        }
        DialogScreen screen = (DialogScreen)class_4372;
        if (((AccessorOrderDialogScreen)screen).anubis$dialog() != view.dialog()) {
            return false;
        }
        HashMap<String, Action.ValueGetter> values = new HashMap<String, Action.ValueGetter>();
        for (Input input : view.dialog().common().inputs()) {
            InputControl class_114382 = input.control();
            if (class_114382 instanceof TextInput) {
                TextInput textInput = (TextInput)class_114382;
                values.put(input.key(), Action.ValueGetter.of((String)text.getOrDefault(input.key(), textInput.initial())));
                continue;
            }
            class_114382 = input.control();
            if (!(class_114382 instanceof BooleanInput)) continue;
            BooleanInput bool = (BooleanInput)class_114382;
            values.put(input.key(), Dialogs.booleanValue(bool));
        }
        Optional<net.minecraft.network.chat.ClickEvent> event = button.raw().action().flatMap(action -> action.createAction(values));
        screen.runAction(event);
        return true;
    }

    static void close(Minecraft mc) {
        Screen class_4372 = mc.gui.screen();
        if (class_4372 instanceof DialogScreen) {
            DialogScreen screen = (DialogScreen)class_4372;
            screen.onClose();
        }
    }

    private static Action.ValueGetter booleanValue(final BooleanInput input) {
        final boolean value = input.initial();
        return new Action.ValueGetter(){

            public String asTemplateSubstitution() {
                return value ? input.onTrue() : input.onFalse();
            }

            public Tag asTag() {
                return ByteTag.valueOf((boolean)value);
            }
        };
    }

    private static boolean isGreen(int rgb) {
        int r = rgb >> 16 & 0xFF;
        int g = rgb >> 8 & 0xFF;
        int b = rgb & 0xFF;
        return g >= 192 && r < 144 && b < 144;
    }

    @Environment(value=EnvType.CLIENT)
    record Button(ActionButton raw, String label, String key, Component component) {
        boolean green() {
            boolean[] found = new boolean[]{false};
            this.component.visit((style, text) -> {
                TextColor color = style.getColor();
                if (!text.isBlank() && color != null && Dialogs.isGreen(color.getValue())) {
                    found[0] = true;
                }
                return Optional.empty();
            }, Style.EMPTY);
            return found[0];
        }
    }

    @Environment(value=EnvType.CLIENT)
    record View(Dialog dialog, String title, List<Button> buttons, List<String> body, List<String> textInputs, List<ItemStack> items) {
        Button find(Predicate<Button> test) {
            for (Button button : this.buttons) {
                if (!test.test(button)) continue;
                return button;
            }
            return null;
        }

        Button labelled(String ... words) {
            for (String word : words) {
                String wanted = ServerText.key(word);
                Button exact = this.find(button -> button.key().equals(wanted));
                if (exact == null) continue;
                return exact;
            }
            for (String word : words) {
                String wanted = ServerText.key(word);
                Button partial = this.find(button -> button.key().contains(wanted));
                if (partial == null) continue;
                return partial;
            }
            return null;
        }

        boolean titled(String text) {
            return this.title.contains(text);
        }

        String describe() {
            ArrayList<String> labels = new ArrayList<String>();
            for (Button button : this.buttons) {
                if (!button.label().isBlank()) {
                    labels.add(button.label());
                }
                if (labels.size() < 12) continue;
                break;
            }
            return "'" + this.title + "' buttons: " + (labels.isEmpty() ? "(none)" : String.join((CharSequence)", ", labels));
        }
    }
}
