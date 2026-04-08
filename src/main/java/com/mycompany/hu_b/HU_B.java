package com.mycompany.hu_b;

import com.mycompany.hu_b.ui.MainFrame;
import javax.swing.SwingUtilities;

public class HU_B {
    public static void main(String[] args) throws Exception {
        SwingUtilities.invokeLater(() -> {
            try {
                new MainFrame();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }
}