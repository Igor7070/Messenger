package com.example.unlimitedpossibilities12.messenger;

/**
 * Цвет кружка у человека без снимка.
 *
 * Цвет считается по имени, поэтому один и тот же человек выглядит одинаково
 * всюду: в контактах, в списке разговоров, в участниках группы и в шапке
 * своей карточки. Те же шесть цветов и тот же счёт — в веб-клиенте.
 */
public final class AvatarColors {

    private static final int[] COLORS = {
            0xFF6366F1, 0xFF0D9488, 0xFF8B5CF6, 0xFF0EA5E9, 0xFFDB2777, 0xFF475569
    };

    private AvatarColors() {
    }

    public static int of(String name) {
        if (name == null || name.isEmpty()) return COLORS[0];
        int hash = 0;
        for (int i = 0; i < name.length(); i++) hash = hash * 31 + name.charAt(i);
        return COLORS[Math.abs(hash) % COLORS.length];
    }
}
