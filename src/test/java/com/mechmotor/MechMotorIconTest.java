package com.mechmotor;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class MechMotorIconTest {

    @Test
    void brandedIconIsPackagedAndReadable() throws IOException {
        try (var stream = MechMotorApplication.class.getResourceAsStream("/icons/mechmotor.png")) {
            assertNotNull(stream, "Window and taskbar icon must ship in mechmotor.jar.");
            var image = ImageIO.read(stream);
            assertNotNull(image, "The branded icon must be a valid PNG.");
            assertTrue(image.getWidth() >= 64 && image.getHeight() >= 64,
                    "The branded Windows icon must be high enough resolution for the taskbar.");
            assertEquals(image.getWidth(), image.getHeight(), "The icon must be square.");
        }
    }
}
