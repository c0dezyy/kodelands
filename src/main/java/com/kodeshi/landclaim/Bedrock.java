package com.kodeshi.landclaim;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.geysermc.cumulus.form.CustomForm;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.cumulus.util.FormImage;
import org.geysermc.floodgate.api.FloodgateApi;

import java.util.List;
import java.util.function.Consumer;

/** Floodgate form helper. Only touched when Floodgate is installed and the player is Bedrock. */
final class Bedrock {
    private Bedrock() {
    }

    static boolean is(Player p) {
        return FloodgateApi.getInstance().isFloodgatePlayer(p.getUniqueId());
    }

    static void menu(LandClaimPlugin pl, Player p, String title, String content,
                     List<Gui.Btn> btns, ConfigurationSection icons) {
        SimpleForm.Builder b = SimpleForm.builder()
                .title(LandClaimPlugin.tr(title))
                .content(LandClaimPlugin.tr(content));
        for (Gui.Btn x : btns) {
            String label = LandClaimPlugin.tr(x.label());
            String ic = icons == null ? null : icons.getString(x.icon());
            if (ic == null || ic.isBlank()) {
                b.button(label);
            } else {
                b.button(label, ic.startsWith("http") ? FormImage.Type.URL : FormImage.Type.PATH, ic);
            }
        }
        b.validResultHandler(r -> {
            int i = r.clickedButtonId();
            if (i >= 0 && i < btns.size()) {
                Bukkit.getScheduler().runTask(pl, btns.get(i).run());
            }
        });
        FloodgateApi.getInstance().sendForm(p.getUniqueId(), b.build());
    }

    static void input(LandClaimPlugin pl, Player p, String title, String label, String hint,
                      Consumer<String> cb) {
        CustomForm form = CustomForm.builder()
                .title(LandClaimPlugin.tr(title))
                .input(LandClaimPlugin.tr(label), hint)
                .validResultHandler(r -> {
                    String s = r.asInput(0);
                    if (s != null && !s.isBlank()) {
                        Bukkit.getScheduler().runTask(pl, () -> cb.accept(s.trim()));
                    }
                })
                .build();
        FloodgateApi.getInstance().sendForm(p.getUniqueId(), form);
    }
}
