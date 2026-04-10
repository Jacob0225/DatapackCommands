package com.zeeesea.mixin;

// Mojang mappings (26.1): MinecraftServer class is in the same package
// net.minecraft.server.MinecraftServer — this is UNCHANGED between Yarn and Mojang for this class.
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public class ExampleMixin {
    /**
     * Mixin target method mapping change (Yarn → Mojang official):
     *   Yarn:   "loadWorld"    (net.minecraft.server.MinecraftServer)
     *   Mojang: "loadLevel"    (net.minecraft.server.MinecraftServer)
     *
     * In 26.1 the game is unobfuscated and IntelliJ + mcsrc.dev can be used
     * to verify the exact method name. The Mojang name for the world-loading
     * entry point is "loadLevel", not "loadWorld".
     *
     * If this mixin target is incorrect at runtime, Mixin will throw a
     * MixinApplyError. Use mcsrc.dev or `./gradlew genSources` to confirm.
     *
     * This mixin body is intentionally empty (it was always a placeholder).
     * If you add real logic here, double-check the exact Mojang method signature.
     */
    @Inject(at = @At("HEAD"), method = "loadLevel")
    private void init(CallbackInfo info) {
        // Placeholder injection — add logic here if needed.
    }
}
