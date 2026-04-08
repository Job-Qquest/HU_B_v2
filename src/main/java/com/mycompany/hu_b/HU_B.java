// Dit is de opstartclass van de applicatie.
// Deze class bevat alleen de main-methode en start het hoofdvenster van de chatbot

package com.mycompany.hu_b;

import com.mycompany.hu_b.ui.AppVenster;
import javax.swing.SwingUtilities;

public class HU_B {
    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeLater(() -> {
            try {
                new AppVenster();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }
}