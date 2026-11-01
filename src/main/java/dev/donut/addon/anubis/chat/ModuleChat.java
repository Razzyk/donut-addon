package dev.donut.addon.anubis.chat;
import dev.donut.addon.anubis.module.ClientModule;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
public final class ModuleChat {
 private ModuleChat() {}
 public static Body body(){return new Body();}
 public static void send(ClientModule owner,Body body){show(owner,body,false);}
 public static void actionBar(ClientModule owner,Body body){show(owner,body,true);}
 private static void show(ClientModule owner,Body body,boolean overlay){
  var mc=Minecraft.getInstance(); var message=Component.literal("["+owner.getName()+"] ").withStyle(ChatFormatting.GOLD).append(body.content);
  mc.execute(()->{
   if (mc.player == null) return;
   if (overlay) mc.gui.hud.setOverlayMessage(message, false);
   else net.ccbluex.liquidbounce.utils.client.ClientChat.chat(message);
  });
 }
 public static final class Body {
  private final MutableComponent content=Component.empty();
  private Body append(Object value,ChatFormatting color){content.append(Component.literal(String.valueOf(value)).withStyle(color));return this;}
  public Body text(String value){return append(value,ChatFormatting.GRAY);} public Body warn(String value){return append(value,ChatFormatting.YELLOW);}
  public Body danger(String value){return append(value,ChatFormatting.RED);} public Body good(String value){return append(value,ChatFormatting.GREEN);}
  public Body value(Object value){return append(value,ChatFormatting.AQUA);} public Body name(String value){return append(value,ChatFormatting.AQUA);}
 }
}
