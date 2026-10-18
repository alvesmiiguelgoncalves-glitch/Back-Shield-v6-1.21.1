package net.backshield.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Configuracao SOMENTE visual (cliente). Arquivo: config/backshield-client.json
 *
 * Quatro perfis TOTALMENTE independentes (mexer em um nunca altera outro):
 *   normal   - escudo nas costas, sem elytra e sem mochila
 *   elytra   - elytra equipada (peitoral ou slot de elytra), em pe
 *   flying   - voando com elytra
 *   backpack - mochila do Backpacked equipada
 *
 * Cada perfil tem x, y, z, rotX, rotY, rotZ e scale. TODOS comecam em 0 e sao valores RELATIVOS a pose base:
 *   x = lateral (+ = direita do jogador visto de tras... teste o sinal no jogo)
 *   y = para BAIXO a partir da linha dos ombros (0 = centro do escudo na altura dos ombros)
 *   z = para TRAS a partir da superficie das costas (0 = centro da placa na superficie)
 *   rotX/rotY/rotZ = graus (aplicados em torno do centro do escudo, na ordem Y, X, Z)
 *   scale = DIFERENCA de tamanho: 0 = tamanho normal do escudo, 0.5 = 50% maior, -0.25 = 25% menor
 * Unidades de posicao em blocos (1 pixel de textura = 0.0625).
 *
 * Ajuste ao vivo: /backshield set <perfil> <campo> <valor>
 */
public final class BackShieldConfig {

    public static final List<String> PROFILES = List.of("normal", "elytra", "flying", "backpack");
    public static final List<String> FIELDS = List.of("x", "y", "z", "rotX", "rotY", "rotZ", "scale");

    private static final Logger LOGGER = LoggerFactory.getLogger("backshield");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("backshield-client.json");
    private static final Path FILE_TMP = FabricLoader.getInstance().getConfigDir().resolve("backshield-client.json.tmp");

    private static volatile BackShieldConfig current = new BackShieldConfig();

    /** Um conjunto de ajustes. Tudo 0 = pose base. */
    public static final class Profile {
        public float x = 0.0F;
        public float y = 0.0F;
        public float z = 0.0F;
        public float rotX = 0.0F;
        public float rotY = 0.0F;
        public float rotZ = 0.0F;
        public float scale = 0.0F;
    }

    public Profile normal = new Profile();
    public Profile elytra = new Profile();
    public Profile flying = new Profile();
    public Profile backpack = new Profile();

    public static BackShieldConfig get() {
        return current;
    }

    public Profile profile(String name) {
        return switch (name) {
            case "normal" -> normal;
            case "elytra" -> elytra;
            case "flying" -> flying;
            case "backpack" -> backpack;
            default -> null;
        };
    }

    public static synchronized void load() {
        BackShieldConfig loaded = null;
        if (Files.exists(FILE)) {
            try {
                String text = Files.readString(FILE, StandardCharsets.UTF_8);
                loaded = GSON.fromJson(text, BackShieldConfig.class);
                // Formato antigo (campos soltos como "height"): guarda uma copia para nao perder os valores.
                if (loaded != null && !text.contains("\"normal\"")) {
                    backup("old");
                    loaded = null;
                }
            } catch (Exception e) {
                // Arquivo ilegivel: NAO sobrescreve sem antes guardar uma copia.
                LOGGER.warn("Could not read {} - keeping a copy as backshield-client.broken.json", FILE, e);
                backup("broken");
                loaded = null;
            }
        }
        current = loaded != null ? loaded : new BackShieldConfig();
        // Perfis ausentes no arquivo viram padrao (0) sem mexer nos outros.
        if (current.normal == null) current.normal = new Profile();
        if (current.elytra == null) current.elytra = new Profile();
        if (current.flying == null) current.flying = new Profile();
        if (current.backpack == null) current.backpack = new Profile();
        save();
    }

    private static void backup(String suffix) {
        try {
            Files.copy(FILE, FILE.resolveSibling("backshield-client." + suffix + ".json"), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOGGER.warn("Could not back up {}", FILE, e);
        }
    }

    /** Grava em arquivo temporario e troca de uma vez, para um crash nunca deixar o arquivo pela metade. */
    public static synchronized void save() {
        try {
            Files.writeString(FILE_TMP, GSON.toJson(current), StandardCharsets.UTF_8);
            try {
                Files.move(FILE_TMP, FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(FILE_TMP, FILE, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOGGER.warn("Could not write {}", FILE, e);
        }
    }

    /** Zera UM perfil (ou todos se name for null). */
    public static synchronized void reset(String name) {
        if (name == null) {
            current = new BackShieldConfig();
        } else if (current.profile(name) != null) {
            switch (name) {
                case "normal" -> current.normal = new Profile();
                case "elytra" -> current.elytra = new Profile();
                case "flying" -> current.flying = new Profile();
                case "backpack" -> current.backpack = new Profile();
                default -> {
                }
            }
        }
        save();
    }

    /** @return true se perfil e campo existem e o valor foi alterado (somente aquele campo daquele perfil). */
    public static synchronized boolean set(String profileName, String fieldName, float value) {
        Profile profile = current.profile(profileName);
        if (profile == null || !FIELDS.contains(fieldName)) {
            return false;
        }
        try {
            Field field = Profile.class.getField(fieldName);
            field.setFloat(profile, value);
            save();
            return true;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    public static List<String> describe(String profileName) {
        List<String> lines = new ArrayList<>();
        for (String name : PROFILES) {
            if (profileName != null && !profileName.equals(name)) {
                continue;
            }
            Profile p = current.profile(name);
            lines.add("[" + name + "] x=" + p.x + " y=" + p.y + " z=" + p.z
                    + " rotX=" + p.rotX + " rotY=" + p.rotY + " rotZ=" + p.rotZ + " scale=" + p.scale);
        }
        return lines;
    }
}
