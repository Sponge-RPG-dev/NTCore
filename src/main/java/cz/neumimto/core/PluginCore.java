package cz.neumimto.core;

import com.google.inject.Inject;
import cz.neumimto.core.ioc.IoC;
import cz.neumimto.core.migrations.DbMigrationService;
import net.minecraft.launchwrapper.Launch;
import org.hibernate.SessionFactory;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.Configuration;
import org.hibernate.cfg.Environment;
import org.hibernate.service.ServiceRegistry;
import org.slf4j.Logger;
import org.spongepowered.api.Game;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.config.DefaultConfig;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.game.state.GameConstructionEvent;
import org.spongepowered.api.event.game.state.GamePreInitializationEvent;
import org.spongepowered.api.event.game.state.GameStoppedServerEvent;
import org.spongepowered.api.plugin.Plugin;
import org.spongepowered.api.plugin.PluginContainer;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

//todo make possible more than one persistence context

/**
 * Created by NeumimTo on 28.11.2015.
 */
@Plugin(id = "nt-core", name = "NT-Core", version = "@VERSION@")
public class PluginCore {

    public static PluginCore Instance;

    @Inject
    public Logger logger;

    @Inject
    @DefaultConfig(sharedRoot = false)
    private Path config;

    private Path path;

    static {

        try {
            ClassLoader classLoader = PluginCore.class.getClassLoader();
            Field invalidClasses = classLoader.getClass().getDeclaredField("invalidClasses");
            invalidClasses.setAccessible(true);
            Field negativeResourceCache = classLoader.getClass().getDeclaredField("negativeResourceCache");
            negativeResourceCache.setAccessible(true);
            Method clear = Set.class.getDeclaredMethod("clear");
            Object o = invalidClasses.get(classLoader);
            clear.invoke(o);
            Object o1 = negativeResourceCache.get(classLoader);
            clear.invoke(o1);
        } catch (NoSuchFieldException | NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            e.printStackTrace();
        }
    }

    private static Map<String, SessionFactory> sessionFactories = new ConcurrentHashMap<>();

    public static void loadJarFile(File f) {
        try {
            Launch.classLoader.addURL(f.toURI().toURL());
        } catch (MalformedURLException e) {
            e.printStackTrace();
        }
    }

    public static URLClassLoader getClassLoader() {
        return Launch.classLoader;
    }

    @Listener
    public void setup(GameConstructionEvent event) {
        Instance = this;
        Game game = Sponge.getGame();
        IoC ioC = IoC.get();
        ioC.registerInterfaceImplementation(Game.class, game);
        ioC.registerInterfaceImplementation(Logger.class, logger);
        PluginContainer implementation = game.getPlatform().getImplementation();

        if (implementation.getName().equalsIgnoreCase("SpongeVanilla")) {
            File folder = config.getParent().toFile();
            for (File file : folder.listFiles()) {
                if (file.getName().endsWith("jar")) {
                    logger.info(file.getName() + " will be added to the classpath.");
                    loadJarFile(file);
                }
            }
        }
    }

    @Listener
    public void setupHibernate(GamePreInitializationEvent event) {
        logger.info("Initializing Hibernate .... ");
        java.util.logging.Logger.getLogger("org.hibernate").setLevel(Level.INFO);
        Path p = copyDBProperties(Sponge.getGame());

        for (File file : p.toFile().listFiles()) {
            String name = file.getName();
            if (name.startsWith("database") && name.endsWith(".properties")) {
                String[] split = name.split("\\.");
                String unit = "*";
                if (split.length == 3) {
                    unit = split[2];
                }
                Properties properties = new Properties();
                try (FileInputStream stream = new FileInputStream(file)) {
                    properties.load(stream);
                } catch (IOException e) {
                    e.printStackTrace();
                }

                /*
                I dont want these to be changeable from config file, so just set them every time
                 */
                properties.put(Environment.ARTIFACT_PROCESSING_ORDER, "class, hbm");
                properties.put(Environment.ENABLE_LAZY_LOAD_NO_TRANS, true);

                /*
                Dont override if setup otherwise
                 */
                if (!properties.containsKey(Environment.HBM2DDL_AUTO)) {
                    properties.put(Environment.HBM2DDL_AUTO, "validate");
                }
                properties.put(Environment.LOG_SESSION_METRICS, false);
                String s = (String) properties.get("hibernate.connection.url");
                if (s == null) {
                    throw new RuntimeException("hibernate.connection.url is missing in database.properties file");
                }

                DbMigrationService build = IoC.get().build(DbMigrationService.class);
                Connection connection = null;
                try {
                    connection = DriverManager.getConnection(s, properties.getProperty(Environment.USER), properties.getProperty(Environment.PASS));
                    build.setConnection(connection);
                    Sponge.getEventManager().post(new FindDbSchemaMigrationsEvent(this, unit));
                    build.startMigration();
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    try {
                        connection.close();
                    } catch (SQLException e) {
                        e.printStackTrace();
                    }
                }

                FindPersistenceContextEvent ev = new FindPersistenceContextEvent(unit);
                Sponge.getEventManager().post(ev);
                Configuration configuration = new Configuration();
                configuration.addProperties(properties);
                ev.getClasses().stream().forEach(configuration::addAnnotatedClass);
                String className = properties.get("hibernate.connection.driver_class").toString();
                try {

                    logger.info("Loading driver class " + className);
                    getClass().getClassLoader().loadClass(className);
                } catch (ClassNotFoundException e) {
                    logger.error("====================================================");
                    logger.error("Class " + className + " not found on the classpath! ");
                    logger.error("Possible causes: ");
                    logger.error("       - The database driver is not on the classpath");
                    logger.error("       - The classname is miss spelled");
                    logger.error("====================================================");
                }
                ServiceRegistry registry = new StandardServiceRegistryBuilder()
                        .applySettings(configuration.getProperties())
                        .build();

                SessionFactory factory = null;
                try {
                    factory = configuration.buildSessionFactory(registry);
                } catch (Exception e) {
                    logger.error("Could not build session factory", e);
                    logger.error("^ This is the relevant part of log you are looking for");
                    factory = new DummySessionFactory();
                }


                IoC.get().registerInterfaceImplementation(SessionFactory.class, factory);
                SessionFactoryCreatedEvent e = new SessionFactoryCreatedEvent(factory);
                Sponge.getEventManager().post(e);
                sessionFactories.put(unit, factory);
            }
        }

    }

    protected Path copyDBProperties(Game game) {
        Path path = Paths.get(config.getParent().toString() + File.separator + "database.properties");
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            InputStream resourceAsStream = getClass().getClassLoader().getResourceAsStream("database.properties");
            try {
                Files.copy(resourceAsStream, path);
                logger.info("File \"database.properties\" has been copied into the config/nt-core folder.");
                logger.info("\u001b[1;32mBy default H2 databse will be used");
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return path.getParent();
    }

    @Listener
    public void close(GameStoppedServerEvent event) {

        // IoC.get().build(SessionFactory.class).close();
    }

    public SessionFactory getSessionFactoryByName(String name) {
        if (sessionFactories.size() == 1) {
            return sessionFactories.values().iterator().next();
        }
        SessionFactory sessionFactory = sessionFactories.get(name);
        if (sessionFactory == null) {
            logger.error("==========================");
            logger.error("Attempted to get a sessionfactory with id " + name);
            logger.error("");
            logger.error("No factory found");
            logger.error("Configure session factory by creating a definition in config/nt-core/database."+name+".properties");
            logger.error("==========================");
        }
        return sessionFactory;
    }
}
