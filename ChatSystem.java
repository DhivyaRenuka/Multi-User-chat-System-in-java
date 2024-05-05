import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class ChatSystem {
    private static final Map<String, String> users = new HashMap<>();
    private static final Set<String> loggedInUsers = new HashSet<>();
    private static final Map<String, ChatWindow> activeChatWindows = new HashMap<>();
    private static final BlockingQueue<String> messageQueue = new LinkedBlockingQueue<>();
    private static final Object lock = new Object(); // Lock for synchronization

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            LoginWindow loginWindow = new LoginWindow();
            loginWindow.loadUserInformation();
            loginWindow.setVisible(true);
        });
        startMessageDispatcher();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            synchronized (lock) {
                clearUsersFile();
            }
        }));
    }

    private static void clearUsersFile() {
        try (FileWriter writer = new FileWriter("users.txt")) {
            // Write an empty string to clear the file
            writer.write("");
            writer.close();
            System.out.println("users.txt file cleared.");
        } catch (IOException e) {
            System.err.println("Error clearing users.txt file: " + e.getMessage());
        }
    }

    private static class LoginWindow extends JFrame {
        private final JTextField usernameField;
        private final JPasswordField passwordField;

        public LoginWindow() {
            setTitle("Login");
            setSize(300, 150);
            setDefaultCloseOperation(EXIT_ON_CLOSE);
            setLocationRelativeTo(null);

            JPanel panel = new JPanel(new BorderLayout());
            panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

            JPanel inputPanel = new JPanel(new GridLayout(2, 2, 5, 5));
            usernameField = new JTextField();
            passwordField = new JPasswordField();
            inputPanel.add(new JLabel("Username:"));
            inputPanel.add(usernameField);
            inputPanel.add(new JLabel("Password:"));
            inputPanel.add(passwordField);

            JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            JButton loginButton = new JButton("Login");
            JButton registerButton = new JButton("Register");
            buttonPanel.add(loginButton);
            buttonPanel.add(registerButton);

            panel.add(inputPanel, BorderLayout.CENTER);
            panel.add(buttonPanel, BorderLayout.SOUTH);

            add(panel);

            registerButton.addActionListener(e -> registerUser());
            loginButton.addActionListener(e -> loginUser());
        }

        private void registerUser() {
            String username = usernameField.getText();
            String password = new String(passwordField.getPassword());
            if (username.isEmpty() || password.isEmpty()) {
                showMessage("Please enter username and password", MessageType.ERROR);
            } else if (users.containsKey(username)) {
                showMessage("Username already exists", MessageType.ERROR);
            } else {
                synchronized (lock) {
                    users.put(username, password);
                    saveUserInformation(); // Save user information to file
                }
                showMessage("Registration successful", MessageType.SUCCESS);
            }
        }

        public void loadUserInformation() {
            try (Scanner scanner = new Scanner(new File("users.txt"))) {
                while (scanner.hasNextLine()) {
                    String[] parts = scanner.nextLine().split(",");
                    users.put(parts[0], parts[1]);
                }
            } catch (IOException e) {
                showMessage("Error loading user information", MessageType.ERROR);
            }
        }

        private void loginUser() {
            String username = usernameField.getText();
            String password = new String(passwordField.getPassword());
            if (users.containsKey(username) && users.get(username).equals(password)) {
                if (!activeChatWindows.containsKey(username)) {
                    loggedInUsers.add(username); // Add user to logged-in users
                    openChatWindow(username); // Open chat window
                }
            } else {
                showMessage("Invalid username or password", MessageType.ERROR);
            }
        }

        private void showMessage(String message, MessageType type) {
            JOptionPane.showMessageDialog(this, message, type.toString(), type.getJOptionPaneMessageType());
        }

        private void saveUserInformation() {
            try (PrintWriter writer = new PrintWriter(new FileWriter("users.txt"))) {
                for (Map.Entry<String, String> entry : users.entrySet()) {
                    writer.println(entry.getKey() + "," + entry.getValue());
                }
            } catch (IOException e) {
                showMessage("Error saving user information", MessageType.ERROR);
            }
        }
    }

    private static class ChatWindow extends JFrame {
        private final JTextArea chatArea;
        private final JTextField messageField;
        private final String currentUser;
        private final JComboBox<String> userComboBox;

        public ChatWindow(String currentUser) {
            this.currentUser = currentUser;
            setTitle("Chat System - Welcome " + currentUser);
            setSize(400, 300);
            setDefaultCloseOperation(DISPOSE_ON_CLOSE); // Dispose window only, don't exit application
            setLocationRelativeTo(null);

            // Initialize chatArea
            chatArea = new JTextArea();
            chatArea.setEditable(false);

            // Initialize userComboBox here
            userComboBox = new JComboBox<>();
            userComboBox.addItem("Everyone");

            updateLoggedInUserList();

            JPanel chatPanel = new JPanel(new BorderLayout());
            chatPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
            JScrollPane scrollPane = new JScrollPane(chatArea);
            chatPanel.add(scrollPane, BorderLayout.CENTER);

            JPanel inputPanel = new JPanel(new BorderLayout());
            messageField = new JTextField();
            JButton sendButton = new JButton("Send");
            inputPanel.add(messageField, BorderLayout.CENTER);
            inputPanel.add(sendButton, BorderLayout.EAST);
            chatPanel.add(inputPanel, BorderLayout.SOUTH);

            JPanel userPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
            userPanel.add(new JLabel("Send to:"));
            userPanel.add(userComboBox);
            chatPanel.add(userPanel, BorderLayout.NORTH);

            add(chatPanel);

            sendButton.addActionListener(e -> sendMessage());
            messageField.addActionListener(e -> sendMessage());

            setVisible(true);
        }

        private void updateLoggedInUserList() {
            userComboBox.removeAllItems();
            userComboBox.addItem("Everyone");
            for (String user : users.keySet()) {
                if (!user.equals(currentUser)) { // Exclude current user
                    userComboBox.addItem(user);
                }
            }
        }

        private void sendMessage() {
            String message = messageField.getText();
            if (!message.isEmpty()) {
                String selectedUser = Objects.requireNonNull(userComboBox.getSelectedItem()).toString();
                if (selectedUser.equals("Everyone")) {
                    messageQueue.offer(currentUser + ": " + message); // Add message to the queue
                } else {
                    if (users.containsKey(selectedUser)) {
                        String privateMessage = "(Private) " + currentUser + " to " + selectedUser + ": " + message;
                        messageQueue.offer(privateMessage); // Add private message to the queue
                    } else {
                        JOptionPane.showMessageDialog(this, "Recipient does not exist!", "Error", JOptionPane.ERROR_MESSAGE);
                    }
                }
                messageField.setText("");
            }
        }

        private void updateChatArea(String message) {
            SwingUtilities.invokeLater(() -> {
                chatArea.append(message + "\n");
                chatArea.setCaretPosition(chatArea.getDocument().getLength()); // Scroll to bottom
            });
        }

    }

    private static void startMessageDispatcher() {
        new Thread(() -> {
            while (true) {
                try {
                    String message = messageQueue.take(); // Block until a message is available
                    // Check if the message format is for private message
                    if (message.startsWith("(Private)")) {
                        // Extract sender, recipient, and message content
                        int senderEndIndex = message.indexOf(" to ");
                        if (senderEndIndex != -1) {
                            String sender = message.substring("(Private)".length(), senderEndIndex).trim();
                            int recipientStartIndex = senderEndIndex + " to ".length();
                            int recipientEndIndex = message.indexOf(": ");
                            if (recipientEndIndex != -1) {
                                String recipient = message.substring(recipientStartIndex, recipientEndIndex).trim();
                                int messageContentStartIndex = recipientEndIndex + " : ".length();
                                String messageContent = message.substring(messageContentStartIndex).trim();
                                // Update chat windows for both sender and recipient
                                final String finalSender = sender;
                                final String finalRecipient = recipient;
                                final String finalMessage = message;
                                SwingUtilities.invokeLater(() -> {
                                    synchronized (activeChatWindows) {
                                        ChatWindow recipientWindow = activeChatWindows.get(finalRecipient);
                                        if (recipientWindow != null) {
                                            recipientWindow.updateChatArea(finalMessage);
                                        }
                                        ChatWindow senderWindow = activeChatWindows.get(finalSender);
                                        if (senderWindow != null) {
                                            senderWindow.updateChatArea(finalMessage);
                                        }
                                    }
                                });
                            }
                        }
                    } else {
                        // Message format for group message: sender: messageContent
                        int colonIndex = message.indexOf(":");
                        if (colonIndex != -1) {
                            String sender = message.substring(0, colonIndex).trim();
                            String messageContent = message.substring(colonIndex + 1).trim();
                            // Update chat windows for all users
                            final String finalMessage = message;
                            SwingUtilities.invokeLater(() -> {
                                synchronized (activeChatWindows) {
                                    for (ChatWindow chatWindow : activeChatWindows.values()) {
                                        chatWindow.updateChatArea(finalMessage);
                                    }
                                }
                            });
                        }
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    private static void openChatWindow(String username) {
        ChatWindow chatWindow = new ChatWindow(username);
        synchronized (activeChatWindows) {
            activeChatWindows.put(username, chatWindow);
        }
    }

    private enum MessageType {
        SUCCESS(JOptionPane.INFORMATION_MESSAGE),
        ERROR(JOptionPane.ERROR_MESSAGE);

        private final int jOptionPaneMessageType;

        MessageType(int jOptionPaneMessageType) {
            this.jOptionPaneMessageType = jOptionPaneMessageType;
        }

        public int getJOptionPaneMessageType() {
            return jOptionPaneMessageType;
        }

        @Override
        public String toString() {
            return name().charAt(0) + name().substring(1).toLowerCase();
        }
    }
}
