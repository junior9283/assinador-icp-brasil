package br.com.vida.assinador.service;

import br.com.vida.assinador.model.BatchCorner;
import br.com.vida.assinador.model.SignaturePlacement;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SignaturePlacementServiceTest {
    @Test
    void placesBatchStampAtBottomRight() {
        SignaturePlacement placement = SignaturePlacementService.forCorner(
                3,
                595,
                842,
                BatchCorner.INFERIOR_DIREITO
        );

        assertEquals(3, placement.page());
        assertEquals(397, placement.x(), 0.001);
        assertEquals(784, placement.y(), 0.001);
        assertEquals(180, placement.width(), 0.001);
        assertEquals(40, placement.height(), 0.001);
    }
}
