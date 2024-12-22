import java.io.*;
import java.net.*;
import java.util.*;

public class ProxyCache {
    // Attributs statiques modifiables
    private static int PROXY_PORT;
    private static String SERVER_IP;
    private static int XAMPP_PORT;
    private static long CACHE_DURATION;

    private static boolean running = true;
    private static final Map<String, CacheEntry> cache = new HashMap<>();

    public static void main(String[] args) {
        loadConfig("donnees.txt");

        System.out.println("Proxy Cache démarré sur le port " + PROXY_PORT);
        try (ServerSocket serverSocket = new ServerSocket(PROXY_PORT)) {
            new Thread(ProxyCache::handleServerCommands).start();
            while (true) {
                Socket clientSocket = serverSocket.accept();
                new Thread(() -> handleClient(clientSocket)).start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void loadConfig(String fileName) {
        Properties properties = new Properties();

        try (BufferedReader reader = new BufferedReader(new FileReader(fileName))) {
            properties.load(reader);

            // Charger les configurations
            PROXY_PORT = Integer.parseInt(properties.getProperty("PROXY_PORT"));
            SERVER_IP = properties.getProperty("SERVER_IP");
            XAMPP_PORT = Integer.parseInt(properties.getProperty("XAMPP_PORT"));
            CACHE_DURATION = Long.parseLong(properties.getProperty("CACHE_DURATION"));

            // Afficher les valeurs chargées pour vérification
            System.out.println("Configuration chargée avec succès :");
            System.out.println("PROXY_PORT = " + PROXY_PORT);
            System.out.println("SERVER_IP = " + SERVER_IP);
            System.out.println("XAMPP_PORT = " + XAMPP_PORT);
            System.out.println("CACHE_DURATION = " + CACHE_DURATION);

        } catch (IOException | NumberFormatException e) {
            System.err.println("Erreur lors de la lecture du fichier de configuration : " + e.getMessage());
        }
    }

    private static void handleServerCommands() {
        try (Scanner scanner = new Scanner(System.in)) {
            while (running) {
                System.out.print("$CommandeProxy > ");
                String command = scanner.nextLine().trim();
    
                if ("listecache".equalsIgnoreCase(command)) {
                    listCache();
                } else if ("drop all".equalsIgnoreCase(command)) { 
                    clearAllCache();
                } else if (command.startsWith("drop ")) { 
                    String key = command.substring(5).trim();
                    deleteCacheEntry(key);
                } else if (command.startsWith("changeduration ")) { 
                    String durationStr = command.substring(15).trim();
                    try {
                        CACHE_DURATION = Long.parseLong(durationStr);
                        System.out.println("Durée de cache modifiée à " + CACHE_DURATION + " ms.");
                    } catch (NumberFormatException e) {
                        System.out.println("Erreur : Veuillez fournir une durée valide en millisecondes.");
                    }
                } else if (command.startsWith("changedurationentry ")) { 
                    String[] parts = command.split(" ");
                    if (parts.length == 3) {
                        String key = parts[1];
                        long duration = Long.parseLong(parts[2]);
                        changeDurationForEntry(key, duration);
                    } else {
                        System.out.println("Commande incorrecte. Utilisation : changedurationentry <clé> <durée>");
                    }
                } else if ("exit".equalsIgnoreCase(command)) {
                    System.out.println("Arrêt du serveur proxy...");
                    running = false;
                    System.exit(1);
                } else {
                    System.out.println("Commande non reconnue.");
                }
            }
        }
    }
    
    private static void changeDurationForEntry(String key, long newDuration) {
        CacheEntry entry = cache.get(key);
        if (entry != null) {
            entry.timestamp = System.currentTimeMillis(); // Réinitialise le timestamp
            entry.timestamp += newDuration; // Met à jour la durée
            System.out.println("Durée du cache mise à jour pour : " + key + " à " + newDuration + " ms.");
        } else {
            System.out.println("Aucune entrée trouvée avec la clé : " + key);
        }
    }
    
    private static void clearAllCache() {
        if (cache.isEmpty()) {
            System.out.println("Le cache est déjà vide.");
        } else {
            cache.clear();
            System.out.println("Tous les caches ont été effacés.");
        }
    }
    

    private static void listCache() {
        if (cache.isEmpty()) {
            System.out.println("Le cache est vide");
        } else {
            System.out.println("Contenu du cache :");
            cache.forEach((key, value) -> System.out.println("- " + key));
        }
    }

    private static void deleteCacheEntry(String key) {
        if (cache.containsKey(key)) {
            cache.remove(key);
            System.out.println("Entrée du cache supprimée : " + key);
        } else {
            System.out.println("Aucune entrée trouvée avec la clé : " + key);
        }
    }

    private static void handleClient(Socket clientSocket) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
             OutputStream out = clientSocket.getOutputStream()) {

            String requestLine = in.readLine();
            System.out.println("Requête reçue : " + requestLine);

            // Vérifier la requête
            if (requestLine == null || !requestLine.startsWith("GET")) {
                sendError(out, "400 Bad Request");
                return;
            }

            // Extraire le chemin du fichier demandé
            String fileRequested = requestLine.split(" ")[1];
            if (fileRequested.startsWith("/")) fileRequested = fileRequested.substring(1);

            // Vérifier dans le cache
            if (cache.containsKey(fileRequested)) {
                CacheEntry cachedEntry = cache.get(fileRequested);
                // Vérifier si le cache n'est pas expiré
                if (System.currentTimeMillis() - cachedEntry.timestamp <= CACHE_DURATION) {
                    System.out.println("Fichier servi depuis le cache: " + fileRequested);
                    out.write(cachedEntry.data);
                    return;
                } else {
                    cache.remove(fileRequested); // Supprimer l'entrée expirée
                    System.out.println("Cache expiré pour: " + fileRequested);
                }
            }

            // Récupérer depuis le serveur et stocker dans le cache
            System.out.println("Récupération depuis le serveur pour: " + fileRequested);
            byte[] content = fetchFromServer(fileRequested);
            if (content != null) {
                cache.put(fileRequested, new CacheEntry(content, System.currentTimeMillis())); // Mettre à jour le cache
                out.write(content);
            } else {
                sendError(out, "404 Not Found");
            }

        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                clientSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private static void sendError(OutputStream out, String errorMessage) throws IOException {
        String errorResponse = "HTTP/1.1 " + errorMessage + "\r\nConnection: close\r\n\r\n" + errorMessage;
        out.write(errorResponse.getBytes());
        out.flush();
    }

    private static byte[] fetchFromServer(String fileRequested) {
        try (Socket serverSocket = new Socket(SERVER_IP, XAMPP_PORT);
             OutputStream serverOutput = serverSocket.getOutputStream();
             InputStream serverInput = serverSocket.getInputStream()) {

            PrintWriter out = new PrintWriter(new OutputStreamWriter(serverOutput, "UTF-8"), true);
            out.print("GET /" + fileRequested + " HTTP/1.1\r\n");
            out.print("Host: " + SERVER_IP + "\r\n");
            out.print("User-Agent: ProxyClient/1.0\r\n");
            out.print("Accept: */*\r\n");
            out.print("Connection: close\r\n");
            out.print("\r\n");
            out.flush();

            ByteArrayOutputStream responseBuffer = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int bytesRead;
            System.out.println("Requête envoyée au serveur : GET /" + fileRequested);

            while ((bytesRead = serverInput.read(buffer)) != -1) {
                responseBuffer.write(buffer, 0, bytesRead);
            }
            return responseBuffer.toByteArray();

        } catch (IOException e) {
            System.err.println("Erreur lors de la requête vers le serveur : " + e.getMessage());
            return null;
        }
    }



    // Classe interne pour gérer les entrées du cache
    private static class CacheEntry {
        byte[] data;
        long timestamp;

        CacheEntry(byte[] data, long timestamp) {
            this.data = data;
            this.timestamp = timestamp;
        }
    }
}
