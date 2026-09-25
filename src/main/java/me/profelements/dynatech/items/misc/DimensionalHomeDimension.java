package me.profelements.dynatech.items.misc;

import java.util.Random;
import org.bukkit.Material;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;

public class DimensionalHomeDimension extends ChunkGenerator {

    @Override
    public void generateNoise(
            @NotNull WorldInfo worldInfo,
            @NotNull Random random,
            int chunkX,
            int chunkZ,
            @NotNull ChunkData chunkData) {

        int minHeight = chunkData.getMinHeight();
        int maxHeight = chunkData.getMaxHeight();

        if (59 >= minHeight && 59 < maxHeight) {
            chunkData.setRegion(0, 59, 0, 16, 60, 16, Material.BEDROCK);
        }

        int wallMinY = Math.max(60, minHeight);
        int wallMaxY = Math.min(180, maxHeight);

        for (int y = wallMinY; y < wallMaxY; y++) {
            for (int x = 0; x < 16; x++) {
                chunkData.setBlock(x, y, 0, Material.BARRIER);
                chunkData.setBlock(x, y, 15, Material.BARRIER);
            }

            for (int z = 0; z < 16; z++) {
                chunkData.setBlock(0, y, z, Material.BARRIER);
                chunkData.setBlock(15, y, z, Material.BARRIER);
            }
        }

        if (180 >= minHeight && 180 < maxHeight) {
            chunkData.setRegion(0, 180, 0, 16, 181, 16, Material.BARRIER);
        }
    }

    @Override
    public boolean shouldGenerateNoise() {
        return false;
    }

    @Override
    public boolean shouldGenerateSurface() {
        return false;
    }

    @Override
    public boolean shouldGenerateCaves() {
        return false;
    }

    @Override
    public boolean shouldGenerateDecorations() {
        return false;
    }

    @Override
    public boolean shouldGenerateMobs() {
        return false;
    }

    @Override
    public boolean shouldGenerateStructures() {
        return false;
    }
}
