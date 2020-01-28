package com.gmail.nossr50.database;

import com.gmail.nossr50.datatypes.MobHealthbarType;
import com.gmail.nossr50.datatypes.database.DatabaseType;
import com.gmail.nossr50.datatypes.database.PlayerStat;
import com.gmail.nossr50.datatypes.database.PoolIdentifier;
import com.gmail.nossr50.datatypes.database.UpgradeType;
import com.gmail.nossr50.datatypes.player.PlayerProfile;
import com.gmail.nossr50.datatypes.player.UniqueDataType;
import com.gmail.nossr50.datatypes.skills.PrimarySkillType;
import com.gmail.nossr50.datatypes.skills.SuperAbilityType;
import com.gmail.nossr50.mcMMO;
import org.apache.tomcat.jdbc.pool.DataSource;
import org.apache.tomcat.jdbc.pool.PoolProperties;

import java.sql.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

public final class SQLDatabaseManager implements DatabaseManager {
    private final mcMMO pluginRef;
    private final String COM_MYSQL_JDBC_DRIVER = "com.mysql.jdbc.Driver";
    private final String ALL_QUERY_VERSION = "total";
    private final Map<UUID, Integer> cachedUserIDs = new HashMap<>();
    private String tablePrefix;
    private DataSource miscPool;
    private DataSource loadPool;
    private DataSource savePool;

    private boolean debug = false;
    //How long since a users last login before we purge them
    long purgeTime;
    // During convertUsers, how often to output a status
    int progressInterval;

    private ReentrantLock massUpdateLock = new ReentrantLock();

    protected SQLDatabaseManager(mcMMO pluginRef) {
        this.pluginRef = pluginRef;

        tablePrefix = pluginRef.getMySQLConfigSettings().getConfigSectionDatabase().getTablePrefix();
        purgeTime = 2630000000L * pluginRef.getDatabaseCleaningSettings().getOldUserCutoffMonths();
        progressInterval = 200;

        String connectionString = "jdbc:mysql://" + pluginRef.getMySQLConfigSettings().getUserConfigSectionServer().getServerAddress()
                + ":" + pluginRef.getMySQLConfigSettings().getUserConfigSectionServer().getServerPort() + "/" + pluginRef.getMySQLConfigSettings().getConfigSectionDatabase().getDatabaseName();

        if (pluginRef.getMySQLConfigSettings().getUserConfigSectionServer().isUseSSL())
            connectionString +=
                    "?verifyServerCertificate=false" +
                            "&useSSL=true" +
                            "&requireSSL=true";
        else
            connectionString +=
                    "?useSSL=false";

        try {
            // Force driver to load if not yet loaded
            Class.forName(COM_MYSQL_JDBC_DRIVER);
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
            return;
            //throw e; // aborts onEnable()  Riking if you want to do this, fully implement it.
        }

        //Setup Save, Load, and Misc pools
        setupPools(connectionString);
        debug = pluginRef.getMySQLConfigSettings().getConfigSectionDatabase().isDebug();

        checkStructure();
    }

    /**
     * Set up our pools
     *
     * @param connectionString the MySQL connection string
     */
    private void setupPools(String connectionString) {
        miscPool = new DataSource(setupPool(PoolIdentifier.MISC, connectionString));
        loadPool = new DataSource(setupPool(PoolIdentifier.LOAD, connectionString));
        savePool = new DataSource(setupPool(PoolIdentifier.SAVE, connectionString));
    }

    /**
     * Sets up our pool using settings from the users config
     *
     * @param poolIdentifier   the target pool
     * @param connectionString the MySQL connection string
     * @return the pool properties ready for conversion
     */
    private PoolProperties setupPool(PoolIdentifier poolIdentifier, String connectionString) {
        PoolProperties poolProperties = new PoolProperties();
        poolProperties.setDriverClassName(COM_MYSQL_JDBC_DRIVER);
        poolProperties.setUrl(connectionString);

        //MySQL User Name
        poolProperties.setUsername(pluginRef.getMySQLConfigSettings().getConfigSectionUser().getUsername());
        //MySQL User Password
        poolProperties.setPassword(pluginRef.getMySQLConfigSettings().getConfigSectionUser().getPassword());

        //Initial Size
        poolProperties.setInitialSize(0);

        //Max Pool Size for Misc
        poolProperties.setMaxIdle(pluginRef.getMySQLConfigSettings().getMaxPoolSize(poolIdentifier));
        //Max Connections for Misc
        poolProperties.setMaxActive(pluginRef.getMySQLConfigSettings().getMaxConnections(poolIdentifier));

        poolProperties.setMaxWait(-1);
        poolProperties.setRemoveAbandoned(true);
        poolProperties.setRemoveAbandonedTimeout(60);
        poolProperties.setTestOnBorrow(true);
        poolProperties.setValidationQuery("SELECT 1");
        poolProperties.setValidationInterval(30000);

        return poolProperties;
    }

    @Override
    public int getDatabaseProgressPrintInterval() {
        return progressInterval;
    }

    public void purgePowerlessUsers() {
        massUpdateLock.lock();
        pluginRef.getLogger().info("Purging powerless users...");

        Connection connection = null;
        Statement statement = null;
        int purged = 0;

        try {
            connection = getConnection(PoolIdentifier.MISC);
            statement = connection.createStatement();

            String startingLevel = String.valueOf(pluginRef.getPlayerLevelingSettings().getConfigSectionLevelingGeneral().getStartingLevel());

            //Purge users who have not leveled from the default level
            purged = statement.executeUpdate("DELETE FROM " + tablePrefix + "skills WHERE "
                    + "taming = " + startingLevel + " AND mining = " + startingLevel + " AND woodcutting = " + startingLevel + " AND repair = " + startingLevel + " "
                    + "AND unarmed = " + startingLevel + " AND herbalism = " + startingLevel + " AND excavation = " + startingLevel + " AND "
                    + "archery = " + startingLevel + " AND swords = " + startingLevel + " AND axes = " + startingLevel + " AND acrobatics = " + startingLevel + " "
                    + "AND fishing = " + startingLevel + " AND alchemy = " + startingLevel + ";");

            //Purge users who have 0 for all levels
            purged += statement.executeUpdate("DELETE FROM " + tablePrefix + "skills WHERE "
                    + "taming = 0 AND mining = 0 AND woodcutting = 0 AND repair = 0 "
                    + "AND unarmed = 0 AND herbalism = 0 AND excavation = 0 AND "
                    + "archery = 0 AND swords = 0 AND axes = 0 AND acrobatics = 0 "
                    + "AND fishing = 0 AND alchemy = 0;");


            statement.executeUpdate("DELETE FROM `" + tablePrefix + "experience` WHERE NOT EXISTS (SELECT * FROM `" + tablePrefix + "skills` `s` WHERE `" + tablePrefix + "experience`.`user_id` = `s`.`user_id`)");
            statement.executeUpdate("DELETE FROM `" + tablePrefix + "huds` WHERE NOT EXISTS (SELECT * FROM `" + tablePrefix + "skills` `s` WHERE `" + tablePrefix + "huds`.`user_id` = `s`.`user_id`)");
            statement.executeUpdate("DELETE FROM `" + tablePrefix + "cooldowns` WHERE NOT EXISTS (SELECT * FROM `" + tablePrefix + "skills` `s` WHERE `" + tablePrefix + "cooldowns`.`user_id` = `s`.`user_id`)");
            statement.executeUpdate("DELETE FROM `" + tablePrefix + "users` WHERE NOT EXISTS (SELECT * FROM `" + tablePrefix + "skills` `s` WHERE `" + tablePrefix + "users`.`id` = `s`.`user_id`)");
        } catch (SQLException ex) {
            printErrors(ex);
        } finally {
            tryClose(statement);
            tryClose(connection);
            massUpdateLock.unlock();
        }

        pluginRef.getLogger().info("Purged " + purged + " users from the database.");
    }

    public void purgeOldUsers() {
        massUpdateLock.lock();
        pluginRef.getLogger().info("Purging inactive users older than " + (purgeTime / 2630000000L) + " months...");

        Connection connection = null;
        Statement statement = null;
        int purged = 0;

        try {
            connection = getConnection(PoolIdentifier.MISC);
            statement = connection.createStatement();

            purged = statement.executeUpdate("DELETE FROM u, e, h, s, c USING " + tablePrefix + "users u " +
                    "JOIN " + tablePrefix + "experience e ON (u.id = e.user_id) " +
                    "JOIN " + tablePrefix + "huds h ON (u.id = h.user_id) " +
                    "JOIN " + tablePrefix + "skills s ON (u.id = s.user_id) " +
                    "JOIN " + tablePrefix + "cooldowns c ON (u.id = c.user_id) " +
                    "WHERE ((UNIX_TIMESTAMP() - lastlogin) > " + purgeTime + ")");
        } catch (SQLException ex) {
            printErrors(ex);
        } finally {
            tryClose(statement);
            tryClose(connection);
            massUpdateLock.unlock();
        }

        pluginRef.getLogger().info("Purged " + purged + " users from the database.");
    }

    public boolean removeUser(String playerName, UUID uuid) {
        boolean success = false;
        Connection connection = null;
        PreparedStatement statement = null;

        try {
            connection = getConnection(PoolIdentifier.MISC);
            statement = connection.prepareStatement("DELETE FROM u, e, h, s, c " +
                    "USING " + tablePrefix + "users u " +
                    "JOIN " + tablePrefix + "experience e ON (u.id = e.user_id) " +
                    "JOIN " + tablePrefix + "huds h ON (u.id = h.user_id) " +
                    "JOIN " + tablePrefix + "skills s ON (u.id = s.user_id) " +
                    "JOIN " + tablePrefix + "cooldowns c ON (u.id = c.user_id) " +
                    "WHERE u.user = ?");

            statement.setString(1, playerName);

            success = statement.executeUpdate() != 0;
        } catch (SQLException ex) {
            printErrors(ex);
        } finally {
            tryClose(statement);
            tryClose(connection);
        }

        if (success) {
            if(uuid != null)
                cleanupUser(uuid);

            pluginRef.getMiscTools().profileCleanup(playerName);
        }

        return success;
    }

    public void cleanupUser(UUID uuid) {
        if(cachedUserIDs.containsKey(uuid))
            cachedUserIDs.remove(uuid);
    }

    public boolean saveUser(PlayerProfile profile) {
        boolean success = true;
        PreparedStatement statement = null;
        Connection connection = null;

        try {
            connection = getConnection(PoolIdentifier.SAVE);

            int id = getUserID(connection, profile.getPlayerName(), profile.getUniqueId());

            if (id == -1) {
                id = newUser(connection, profile.getPlayerName(), profile.getUniqueId());
                if (id == -1) {
                    pluginRef.getLogger().severe("Failed to create new account for " + profile.getPlayerName());
                    return false;
                }
            }

            statement = connection.prepareStatement("UPDATE " + tablePrefix + "users SET lastlogin = UNIX_TIMESTAMP() WHERE id = ?");
            statement.setInt(1, id);
            success &= (statement.executeUpdate() != 0);
            statement.close();
            if (!success) {
                pluginRef.getLogger().severe("Failed to update last login for " + profile.getPlayerName());
                return false;
            }

            statement = connection.prepareStatement("UPDATE " + tablePrefix + "skills SET "
                    + " taming = ?, mining = ?, repair = ?, woodcutting = ?"
                    + ", unarmed = ?, herbalism = ?, excavation = ?"
                    + ", archery = ?, swords = ?, axes = ?, acrobatics = ?"
                    + ", fishing = ?, alchemy = ?, total = ? WHERE user_id = ?");
            statement.setInt(1, profile.getSkillLevel(PrimarySkillType.TAMING));
            statement.setInt(2, profile.getSkillLevel(PrimarySkillType.MINING));
            statement.setInt(3, profile.getSkillLevel(PrimarySkillType.REPAIR));
            statement.setInt(4, profile.getSkillLevel(PrimarySkillType.WOODCUTTING));
            statement.setInt(5, profile.getSkillLevel(PrimarySkillType.UNARMED));
            statement.setInt(6, profile.getSkillLevel(PrimarySkillType.HERBALISM));
            statement.setInt(7, profile.getSkillLevel(PrimarySkillType.EXCAVATION));
            statement.setInt(8, profile.getSkillLevel(PrimarySkillType.ARCHERY));
            statement.setInt(9, profile.getSkillLevel(PrimarySkillType.SWORDS));
            statement.setInt(10, profile.getSkillLevel(PrimarySkillType.AXES));
            statement.setInt(11, profile.getSkillLevel(PrimarySkillType.ACROBATICS));
            statement.setInt(12, profile.getSkillLevel(PrimarySkillType.FISHING));
            statement.setInt(13, profile.getSkillLevel(PrimarySkillType.ALCHEMY));
            int total = 0;
            for (PrimarySkillType primarySkillType : pluginRef.getSkillTools().NON_CHILD_SKILLS)
                total += profile.getSkillLevel(primarySkillType);
            statement.setInt(14, total);
            statement.setInt(15, id);
            success &= (statement.executeUpdate() != 0);
            statement.close();
            if (!success) {
                pluginRef.getLogger().severe("Failed to update skills for " + profile.getPlayerName());
                return false;
            }

            statement = connection.prepareStatement("UPDATE " + tablePrefix + "experience SET "
                    + " taming = ?, mining = ?, repair = ?, woodcutting = ?"
                    + ", unarmed = ?, herbalism = ?, excavation = ?"
                    + ", archery = ?, swords = ?, axes = ?, acrobatics = ?"
                    + ", fishing = ?, alchemy = ? WHERE user_id = ?");
            statement.setInt(1, profile.getSkillXpLevel(PrimarySkillType.TAMING));
            statement.setInt(2, profile.getSkillXpLevel(PrimarySkillType.MINING));
            statement.setInt(3, profile.getSkillXpLevel(PrimarySkillType.REPAIR));
            statement.setInt(4, profile.getSkillXpLevel(PrimarySkillType.WOODCUTTING));
            statement.setInt(5, profile.getSkillXpLevel(PrimarySkillType.UNARMED));
            statement.setInt(6, profile.getSkillXpLevel(PrimarySkillType.HERBALISM));
            statement.setInt(7, profile.getSkillXpLevel(PrimarySkillType.EXCAVATION));
            statement.setInt(8, profile.getSkillXpLevel(PrimarySkillType.ARCHERY));
            statement.setInt(9, profile.getSkillXpLevel(PrimarySkillType.SWORDS));
            statement.setInt(10, profile.getSkillXpLevel(PrimarySkillType.AXES));
            statement.setInt(11, profile.getSkillXpLevel(PrimarySkillType.ACROBATICS));
            statement.setInt(12, profile.getSkillXpLevel(PrimarySkillType.FISHING));
            statement.setInt(13, profile.getSkillXpLevel(PrimarySkillType.ALCHEMY));
            statement.setInt(14, id);
            success &= (statement.executeUpdate() != 0);
            statement.close();
            if (!success) {
                pluginRef.getLogger().severe("Failed to update experience for " + profile.getPlayerName());
                return false;
            }

            statement = connection.prepareStatement("UPDATE " + tablePrefix + "cooldowns SET "
                    + "  mining = ?, woodcutting = ?, unarmed = ?"
                    + ", herbalism = ?, excavation = ?, swords = ?"
                    + ", axes = ?, blast_mining = ?, chimaera_wing = ? WHERE user_id = ?");
            statement.setLong(1, profile.getAbilityDATS(SuperAbilityType.SUPER_BREAKER));
            statement.setLong(2, profile.getAbilityDATS(SuperAbilityType.TREE_FELLER));
            statement.setLong(3, profile.getAbilityDATS(SuperAbilityType.BERSERK));
            statement.setLong(4, profile.getAbilityDATS(SuperAbilityType.GREEN_TERRA));
            statement.setLong(5, profile.getAbilityDATS(SuperAbilityType.GIGA_DRILL_BREAKER));
            statement.setLong(6, profile.getAbilityDATS(SuperAbilityType.SERRATED_STRIKES));
            statement.setLong(7, profile.getAbilityDATS(SuperAbilityType.SKULL_SPLITTER));
            statement.setLong(8, profile.getAbilityDATS(SuperAbilityType.BLAST_MINING));
            statement.setLong(9, profile.getUniqueData(UniqueDataType.CHIMAERA_WING_DATS));
            statement.setInt(10, id);
            success = (statement.executeUpdate() != 0);
            statement.close();
            if (!success) {
                pluginRef.getLogger().severe("Failed to update cooldowns for " + profile.getPlayerName());
                return false;
            }

            statement = connection.prepareStatement("UPDATE " + tablePrefix + "huds SET mobhealthbar = ?, scoreboardtips = ? WHERE user_id = ?");
            statement.setString(1, profile.getMobHealthbarType() == null ? pluginRef.getConfigManager().getConfigMobs().getCombat().getHealthBars().getDisplayBarType().name() : profile.getMobHealthbarType().name());
            statement.setInt(2, profile.getScoreboardTipsShown());
            statement.setInt(3, id);
            success = (statement.executeUpdate() != 0);
            statement.close();
            if (!success) {
                pluginRef.getLogger().severe("Failed to update hud settings for " + profile.getPlayerName());
                return false;
            }
        } catch (SQLException ex) {
            printErrors(ex);
        } finally {
            tryClose(statement);
            tryClose(connection);
        }

        return success;
    }

    public List<PlayerStat> readLeaderboard(PrimarySkillType skill, int pageNumber, int statsPerPage) {
        List<PlayerStat> stats = new ArrayList<>();

        String query = skill == null ? ALL_QUERY_VERSION : skill.name().toLowerCase(Locale.ENGLISH);
        ResultSet resultSet = null;
        PreparedStatement statement = null;
        Connection connection = null;

        try {
            connection = getConnection(PoolIdentifier.MISC);
            statement = connection.prepareStatement("SELECT " + query + ", user FROM " + tablePrefix + "users JOIN " + tablePrefix + "skills ON (user_id = id) WHERE " + query + " > 0 AND NOT user = '\\_INVALID\\_OLD\\_USERNAME\\_' ORDER BY " + query + " DESC, user LIMIT ?, ?");
            statement.setInt(1, (pageNumber * statsPerPage) - statsPerPage);
            statement.setInt(2, statsPerPage);
            resultSet = statement.executeQuery();

            while (resultSet.next()) {
                ArrayList<String> column = new ArrayList<>();

                for (int i = 1; i <= resultSet.getMetaData().getColumnCount(); i++) {
                    column.add(resultSet.getString(i));
                }

                stats.add(new PlayerStat(column.get(1), Integer.valueOf(column.get(0))));
            }
        } catch (SQLException ex) {
            printErrors(ex);
        } finally {
            tryClose(resultSet);
            tryClose(statement);
            tryClose(connection);
        }

        return stats;
    }

    public Map<PrimarySkillType, Integer> readRank(String playerName) {
        Map<PrimarySkillType, Integer> skills = new HashMap<>();

        ResultSet resultSet = null;
        PreparedStatement statement = null;
        Connection connection = null;

        try {
            connection = getConnection(PoolIdentifier.MISC);
            for (PrimarySkillType primarySkillType : pluginRef.getSkillTools().NON_CHILD_SKILLS) {
                String skillName = primarySkillType.name().toLowerCase(Locale.ENGLISH);
                // Get count of all users with higher skill level than player
                String sql = "SELECT COUNT(*) AS 'rank' FROM " + tablePrefix + "users JOIN " + tablePrefix + "skills ON user_id = id WHERE " + skillName + " > 0 " +
                        "AND " + skillName + " > (SELECT " + skillName + " FROM " + tablePrefix + "users JOIN " + tablePrefix + "skills ON user_id = id " +
                        "WHERE user = ?)";

                statement = connection.prepareStatement(sql);
                statement.setString(1, playerName);
                resultSet = statement.executeQuery();

                resultSet.next();

                int rank = resultSet.getInt("rank");

                // Ties are settled by alphabetical order
                sql = "SELECT user, " + skillName + " FROM " + tablePrefix + "users JOIN " + tablePrefix + "skills ON user_id = id WHERE " + skillName + " > 0 " +
                        "AND " + skillName + " = (SELECT " + skillName + " FROM " + tablePrefix + "users JOIN " + tablePrefix + "skills ON user_id = id " +
                        "WHERE user = '" + playerName + "') ORDER BY user";

                resultSet.close();
                statement.close();

                statement = connection.prepareStatement(sql);
                resultSet = statement.executeQuery();

                while (resultSet.next()) {
                    if (resultSet.getString("user").equalsIgnoreCase(playerName)) {
                        skills.put(primarySkillType, rank + resultSet.getRow());
                        break;
                    }
                }

                resultSet.close();
                statement.close();
            }

            String sql = "SELECT COUNT(*) AS 'rank' FROM " + tablePrefix + "users JOIN " + tablePrefix + "skills ON user_id = id " +
                    "WHERE " + ALL_QUERY_VERSION + " > 0 " +
                    "AND " + ALL_QUERY_VERSION + " > " +
                    "(SELECT " + ALL_QUERY_VERSION + " " +
                    "FROM " + tablePrefix + "users JOIN " + tablePrefix + "skills ON user_id = id WHERE user = ?)";

            statement = connection.prepareStatement(sql);
            statement.setString(1, playerName);
            resultSet = statement.executeQuery();

            resultSet.next();

            int rank = resultSet.getInt("rank");

            resultSet.close();
            statement.close();

            sql = "SELECT user, " + ALL_QUERY_VERSION + " " +
                    "FROM " + tablePrefix + "users JOIN " + tablePrefix + "skills ON user_id = id " +
                    "WHERE " + ALL_QUERY_VERSION + " > 0 " +
                    "AND " + ALL_QUERY_VERSION + " = " +
                    "(SELECT " + ALL_QUERY_VERSION + " " +
                    "FROM " + tablePrefix + "users JOIN " + tablePrefix + "skills ON user_id = id WHERE user = ?) ORDER BY user";

            statement = connection.prepareStatement(sql);
            statement.setString(1, playerName);
            resultSet = statement.executeQuery();

            while (resultSet.next()) {
                if (resultSet.getString("user").equalsIgnoreCase(playerName)) {
                    skills.put(null, rank + resultSet.getRow());
                    break;
                }
            }

            resultSet.close();
            statement.close();
        } catch (SQLException ex) {
            printErrors(ex);
        } finally {
            tryClose(resultSet);
            tryClose(statement);
            tryClose(connection);
        }

        return skills;
    }

    public void newUser(String playerName, UUID uuid) {
        Connection connection = null;

        try {
            connection = getConnection(PoolIdentifier.MISC);
            newUser(connection, playerName, uuid);
        } catch (SQLException ex) {
            printErrors(ex);
        } finally {
            tryClose(connection);
        }
    }

    private int newUser(Connection connection, String playerName, UUID uuid) {
        ResultSet resultSet = null;
        PreparedStatement statement = null;

        try {
            statement = connection.prepareStatement(
                    "UPDATE `" + tablePrefix + "users` "
                            + "SET user = ? "
                            + "WHERE user = ?");
            statement.setString(1, "_INVALID_OLD_USERNAME_");
            statement.setString(2, playerName);
            statement.executeUpdate();
            statement.close();
            statement = connection.prepareStatement("INSERT INTO " + tablePrefix + "users (user, uuid, lastlogin) VALUES (?, ?, UNIX_TIMESTAMP())", Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, playerName);
            statement.setString(2, uuid != null ? uuid.toString() : null);
            statement.executeUpdate();

            resultSet = statement.getGeneratedKeys();

            if (!resultSet.next()) {
                pluginRef.getLogger().severe("Unable to create new user account in DB");
                return -1;
            }

            writeMissingRows(connection, resultSet.getInt(1));
            return resultSet.getInt(1);
        } catch (SQLException ex) {
            printErrors(ex);
        } finally {
            tryClose(resultSet);
            tryClose(statement);
        }
        return -1;
    }

    @Deprecated
    public PlayerProfile loadPlayerProfile(String playerName, boolean create) {
        return loadPlayerProfile(playerName, null, false, true);
    }

    public PlayerProfile loadPlayerProfile(UUID uuid) {
        return loadPlayerProfile("", uuid, false, true);
    }

    public PlayerProfile loadPlayerProfile(String playerName, UUID uuid, boolean create) {
        return loadPlayerProfile(playerName, uuid, create, true);
    }

    private PlayerProfile loadPlayerProfile(String playerName, UUID uuid, boolean create, boolean retry) {
        PreparedStatement statement = null;
        Connection connection = null;
        ResultSet resultSet = null;

        try {
            connection = getConnection(PoolIdentifier.LOAD);
            int id = getUserID(connection, playerName, uuid);

            if (id == -1) {
                // There is no such user
                if (create) {
                    id = newUser(connection, playerName, uuid);
                    create = false;
                    if (id == -1) {
                        return new PlayerProfile(pluginRef, playerName, uuid, false);
                    }
                } else {
                    return new PlayerProfile(pluginRef, playerName, uuid,false);
                }
            }
            // There is such a user
            writeMissingRows(connection, id);

            statement = connection.prepareStatement(
                    "SELECT "
                            + "s.taming, s.mining, s.repair, s.woodcutting, s.unarmed, s.herbalism, s.excavation, s.archery, s.swords, s.axes, s.acrobatics, s.fishing, s.alchemy, "
                            + "e.taming, e.mining, e.repair, e.woodcutting, e.unarmed, e.herbalism, e.excavation, e.archery, e.swords, e.axes, e.acrobatics, e.fishing, e.alchemy, "
                            + "c.taming, c.mining, c.repair, c.woodcutting, c.unarmed, c.herbalism, c.excavation, c.archery, c.swords, c.axes, c.acrobatics, c.blast_mining, c.chimaera_wing, "
                            + "h.mobhealthbar, h.scoreboardtips, u.uuid, u.user "
                            + "FROM " + tablePrefix + "users u "
                            + "JOIN " + tablePrefix + "skills s ON (u.id = s.user_id) "
                            + "JOIN " + tablePrefix + "experience e ON (u.id = e.user_id) "
                            + "JOIN " + tablePrefix + "cooldowns c ON (u.id = c.user_id) "
                            + "JOIN " + tablePrefix + "huds h ON (u.id = h.user_id) "
                            + "WHERE u.id = ?");
            statement.setInt(1, id);

            resultSet = statement.executeQuery();

            if (resultSet.next()) {
                try {
                    PlayerProfile profile = loadFromResult(playerName, resultSet);
                    String name = resultSet.getString(42); // TODO: Magic Number, make sure it stays updated
                    resultSet.close();
                    statement.close();

                    if (!playerName.isEmpty() && !playerName.equalsIgnoreCase(name) && uuid != null) {
                        statement = connection.prepareStatement(
                                "UPDATE `" + tablePrefix + "users` "
                                        + "SET user = ? "
                                        + "WHERE user = ?");
                        statement.setString(1, "_INVALID_OLD_USERNAME_");
                        statement.setString(2, name);
                        statement.executeUpdate();
                        statement.close();
                        statement = connection.prepareStatement(
                                "UPDATE `" + tablePrefix + "users` "
                                        + "SET user = ?, uuid = ? "
                                        + "WHERE id = ?");
                        statement.setString(1, playerName);
                        statement.setString(2, uuid.toString());
                        statement.setInt(3, id);
                        statement.executeUpdate();
                        statement.close();
                    }

                    return profile;
                } catch (SQLException e) {
                    printErrors(e);
                }
            }
            resultSet.close();
        } catch (SQLException ex) {
            printErrors(ex);
        } finally {
            tryClose(resultSet);
            tryClose(statement);
            tryClose(connection);
        }

        // Problem, nothing was returned

        // return unloaded profile
        if (!retry) {
            return new PlayerProfile(pluginRef, playerName, uuid, false);
        }

        // Retry, and abort on re-failure
        return loadPlayerProfile(playerName, uuid, create, false);
    }

    public void convertUsers(DatabaseManager destination) {
        PreparedStatement statement = null;
        Connection connection = null;
        ResultSet resultSet = null;

        try {
            connection = getConnection(PoolIdentifier.MISC);
            statement = connection.prepareStatement(
                    "SELECT "
                            + "s.taming, s.mining, s.repair, s.woodcutting, s.unarmed, s.herbalism, s.excavation, s.archery, s.swords, s.axes, s.acrobatics, s.fishing, s.alchemy, "
                            + "e.taming, e.mining, e.repair, e.woodcutting, e.unarmed, e.herbalism, e.excavation, e.archery, e.swords, e.axes, e.acrobatics, e.fishing, e.alchemy, "
                            + "c.taming, c.mining, c.repair, c.woodcutting, c.unarmed, c.herbalism, c.excavation, c.archery, c.swords, c.axes, c.acrobatics, c.blast_mining, c.chimaera_wing, "
                            + "h.mobhealthbar, h.scoreboardtips, u.uuid "
                            + "FROM " + tablePrefix + "users u "
                            + "JOIN " + tablePrefix + "skills s ON (u.id = s.user_id) "
                            + "JOIN " + tablePrefix + "experience e ON (u.id = e.user_id) "
                            + "JOIN " + tablePrefix + "cooldowns c ON (u.id = c.user_id) "
                            + "JOIN " + tablePrefix + "huds h ON (u.id = h.user_id) "
                            + "WHERE u.user = ?");
            List<String> usernames = getStoredUsers();
            int convertedUsers = 0;
            long startMillis = System.currentTimeMillis();
            for (String playerName : usernames) {
                statement.setString(1, playerName);
                try {
                    resultSet = statement.executeQuery();
                    resultSet.next();
                    destination.saveUser(loadFromResult(playerName, resultSet));
                    resultSet.close();
                } catch (SQLException e) {
                    printErrors(e);
                    // Ignore
                }
                convertedUsers++;
                printProgress(convertedUsers, startMillis, pluginRef.getLogger());
            }
        } catch (SQLException e) {
            printErrors(e);
        } finally {
            tryClose(resultSet);
            tryClose(statement);
            tryClose(connection);
        }

    }

    public boolean saveUserUUID(String userName, UUID uuid) {
        PreparedStatement statement = null;
        Connection connection = null;

        try {
            connection = getConnection(PoolIdentifier.MISC);
            statement = connection.prepareStatement(
                    "UPDATE `" + tablePrefix + "users` SET "
                            + "  uuid = ? WHERE user = ?");
            statement.setString(1, uuid.toString());
            statement.setString(2, userName);
            statement.execute();
            return true;
        } catch (SQLException ex) {
            printErrors(ex);
            return false;
        } finally {
            tryClose(statement);
            tryClose(connection);
        }
    }

    public boolean saveUserUUIDs(Map<String, UUID> fetchedUUIDs) {
        PreparedStatement statement = null;
        int count = 0;

        Connection connection = null;

        try {
            connection = getConnection(PoolIdentifier.MISC);
            statement = connection.prepareStatement("UPDATE " + tablePrefix + "users SET uuid = ? WHERE user = ?");

            for (Map.Entry<String, UUID> entry : fetchedUUIDs.entrySet()) {
                statement.setString(1, entry.getValue().toString());
                statement.setString(2, entry.getKey());

                statement.addBatch();

                count++;

                if ((count % 500) == 0) {
                    statement.executeBatch();
                    count = 0;
                }
            }

            if (count != 0) {
                statement.executeBatch();
            }

            return true;
        } catch (SQLException ex) {
            printErrors(ex);
            return false;
        } finally {
            tryClose(statement);
            tryClose(connection);
        }
    }

    public List<String> getStoredUsers() {
        ArrayList<String> users = new ArrayList<>();

        Statement statement = null;
        Connection connection = null;
        ResultSet resultSet = null;

        try {
            connection = getConnection(PoolIdentifier.MISC);
            statement = connection.createStatement();
            resultSet = statement.executeQuery("SELECT user FROM " + tablePrefix + "users");
            while (resultSet.next()) {
                users.add(resultSet.getString("user"));
            }
        } catch (SQLException e) {
            printErrors(e);
        } finally {
            tryClose(resultSet);
            tryClose(statement);
            tryClose(connection);
        }

        return users;
    }

    /**
     * Checks that the database structure is present and correct
     */
    private void checkStructure() {

        PreparedStatement statement = null;
        Statement createStatement = null;
        ResultSet resultSet = null;
        Connection connection = null;

        try {
            connection = getConnection(PoolIdentifier.MISC);
            statement = connection.prepareStatement("SELECT table_name FROM INFORMATION_SCHEMA.TABLES"
                    + " WHERE table_schema = ?"
                    + " AND table_name = ?");
            //Database name
            statement.setString(1, pluginRef.getMySQLConfigSettings().getConfigSectionDatabase().getDatabaseName());
            statement.setString(2, tablePrefix + "users");
            resultSet = statement.executeQuery();
            if (!resultSet.next()) {
                createStatement = connection.createStatement();
                createStatement.executeUpdate("CREATE TABLE IF NOT EXISTS `" + tablePrefix + "users` ("
                        + "`id` int(10) unsigned NOT NULL AUTO_INCREMENT,"
                        + "`user` varchar(40) NOT NULL,"
                        + "`uuid` varchar(36) NULL DEFAULT NULL,"
                        + "`lastlogin` int(32) unsigned NOT NULL,"
                        + "PRIMARY KEY (`id`),"
                        + "INDEX(`user`(20) ASC),"
                        + "UNIQUE KEY `uuid` (`uuid`)) DEFAULT CHARSET=latin1 AUTO_INCREMENT=1;");
                tryClose(createStatement);
            }
            tryClose(resultSet);
            //Database name
            statement.setString(1, pluginRef.getMySQLConfigSettings().getConfigSectionDatabase().getDatabaseName());
            statement.setString(2, tablePrefix + "huds");
            resultSet = statement.executeQuery();
            if (!resultSet.next()) {
                createStatement = connection.createStatement();
                createStatement.executeUpdate("CREATE TABLE IF NOT EXISTS `" + tablePrefix + "huds` ("
                        + "`user_id` int(10) unsigned NOT NULL,"
                        + "`mobhealthbar` varchar(50) NOT NULL DEFAULT '" + pluginRef.getConfigManager().getConfigMobs().getCombat().getHealthBars().getDisplayBarType() + "',"
                        + "`scoreboardtips` int(10) NOT NULL DEFAULT '0',"
                        + "PRIMARY KEY (`user_id`)) "
                        + "DEFAULT CHARSET=latin1;");
                tryClose(createStatement);
            }
            tryClose(resultSet);
            //Database name
            statement.setString(1, pluginRef.getMySQLConfigSettings().getConfigSectionDatabase().getDatabaseName());
            statement.setString(2, tablePrefix + "cooldowns");
            resultSet = statement.executeQuery();
            if (!resultSet.next()) {
                createStatement = connection.createStatement();
                createStatement.executeUpdate("CREATE TABLE IF NOT EXISTS `" + tablePrefix + "cooldowns` ("
                        + "`user_id` int(10) unsigned NOT NULL,"
                        + "`taming` int(32) unsigned NOT NULL DEFAULT '0',"
                        + "`mining` int(32) unsigned NOT NULL DEFAULT '0',"
                        + "`woodcutting` int(32) unsigned NOT NULL DEFAULT '0',"
                        + "`repair` int(32) unsigned NOT NULL DEFAULT '0',"
                        + "`unarmed` int(32) unsigned NOT NULL DEFAULT '0',"
                        + "`herbalism` int(32) unsigned NOT NULL DEFAULT '0',"
                        + "`excavation` int(32) unsigned NOT NULL DEFAULT '0',"
                        + "`archery` int(32) unsigned NOT NULL DEFAULT '0',"
                        + "`swords` int(32) unsigned NOT NULL DEFAULT '0',"
                        + "`axes` int(32) unsigned NOT NULL DEFAULT '0',"
                        + "`acrobatics` int(32) unsigned NOT NULL DEFAULT '0',"
                        + "`blast_mining` int(32) unsigned NOT NULL DEFAULT '0',"
                        + "`chimaera_wing` int(32) unsigned NOT NULL DEFAULT '0',"
                        + "PRIMARY KEY (`user_id`)) "
                        + "DEFAULT CHARSET=latin1;");
                tryClose(createStatement);
            }
            tryClose(resultSet);
            //Database name
            statement.setString(1, pluginRef.getMySQLConfigSettings().getConfigSectionDatabase().getDatabaseName());
            statement.setString(2, tablePrefix + "skills");
            resultSet = statement.executeQuery();
            if (!resultSet.next()) {
                String startingLevel = "'" + pluginRef.getPlayerLevelingSettings().getConfigSectionLevelingGeneral().getStartingLevel() + "'";
                String totalLevel = "'" + (pluginRef.getPlayerLevelingSettings().getConfigSectionLevelingGeneral().getStartingLevel() * (PrimarySkillType.values().length - pluginRef.getSkillTools().COMBAT_SKILLS.size())) + "'";
                createStatement = connection.createStatement();
                createStatement.executeUpdate("CREATE TABLE IF NOT EXISTS `" + tablePrefix + "skills` ("
                        + "`user_id` int(10) unsigned NOT NULL,"
                        + "`taming` int(10) unsigned NOT NULL DEFAULT " + startingLevel + ","
                        + "`mining` int(10) unsigned NOT NULL DEFAULT " + startingLevel + ","
                        + "`woodcutting` int(10) unsigned NOT NULL DEFAULT " + startingLevel + ","
                        + "`repair` int(10) unsigned NOT NULL DEFAULT " + startingLevel + ","
                        + "`unarmed` int(10) unsigned NOT NULL DEFAULT " + startingLevel + ","
                        + "`herbalism` int(10) unsigned NOT NULL DEFAULT " + startingLevel + ","
                        + "`excavation` int(10) unsigned NOT NULL DEFAULT " + startingLevel + ","
                        + "`archery` int(10) unsigned NOT NULL DEFAULT " + startingLevel + ","
                        + "`swords` int(10) unsigned NOT NULL DEFAULT " + startingLevel + ","
                        + "`axes` int(10) unsigned NOT NULL DEFAULT " + startingLevel + ","
                        + "`acrobatics` int(10) unsigned NOT NULL DEFAULT " + startingLevel + ","
                        + "`fishing` int(10) unsigned NOT NULL DEFAULT " + startingLevel + ","
                        + "`alchemy` int(10) unsigned NOT NULL DEFAULT " + startingLevel + ","
                        + "`total` int(10) unsigned NOT NULL DEFAULT " + totalLevel + ","
                        + "PRIMARY KEY (`user_id`)) "
                        + "DEFAULT CHARSET=latin1;");
                tryClose(createStatement);
            }
            tryClose(resultSet);
            //Database name
            statement.setString(1, pluginRef.getMySQLConfigSettings().getConfigSectionDatabase().getDatabaseName());
            statement.setString(2, tablePrefix + "experience");
            resultSet = statement.executeQuery();
            if (!resultSet.next()) {
                createStatement = connection.createStatement();
                createStatement.executeUpdate("CREATE TABLE IF NOT EXISTS `" + tablePrefix + "experience` ("
                        + "`user_id` int(10) unsigned NOT NULL,"
                        + "`taming` int(10) unsigned NOT NULL DEFAULT '0',"
                        + "`mining` int(10) unsigned NOT NULL DEFAULT '0',"
                        + "`woodcutting` int(10) unsigned NOT NULL DEFAULT '0',"
                        + "`repair` int(10) unsigned NOT NULL DEFAULT '0',"
                        + "`unarmed` int(10) unsigned NOT NULL DEFAULT '0',"
                        + "`herbalism` int(10) unsigned NOT NULL DEFAULT '0',"
                        + "`excavation` int(10) unsigned NOT NULL DEFAULT '0',"
                        + "`archery` int(10) unsigned NOT NULL DEFAULT '0',"
                        + "`swords` int(10) unsigned NOT NULL DEFAULT '0',"
                        + "`axes` int(10) unsigned NOT NULL DEFAULT '0',"
                        + "`acrobatics` int(10) unsigned NOT NULL DEFAULT '0',"
                        + "`fishing` int(10) unsigned NOT NULL DEFAULT '0',"
                        + "`alchemy` int(10) unsigned NOT NULL DEFAULT '0',"
                        + "PRIMARY KEY (`user_id`)) "
                        + "DEFAULT CHARSET=latin1;");
                tryClose(createStatement);
            }
            tryClose(resultSet);
            tryClose(statement);

            for (UpgradeType updateType : UpgradeType.values()) {
                checkDatabaseStructure(connection, updateType);
            }

            //Level Cap Stuff
            if (pluginRef.getPlayerLevelingSettings().getConfigSectionLevelCaps().getReducePlayerSkillsAboveCap()) {
                for (PrimarySkillType skill : pluginRef.getSkillTools().NON_CHILD_SKILLS) {
                    if (!pluginRef.getPlayerLevelingSettings().isSkillLevelCapEnabled(skill))
                        continue;

                    //Shrink skills above the cap
                    int cap = pluginRef.getPlayerLevelingSettings().getSkillLevelCap(skill);
                    statement = connection.prepareStatement("UPDATE `" + tablePrefix + "skills` SET `" + skill.name().toLowerCase(Locale.ENGLISH) + "` = " + cap + " WHERE `" + skill.name().toLowerCase(Locale.ENGLISH) + "` > " + cap);
                    statement.executeUpdate();
                    tryClose(statement);
                }
            }

            pluginRef.getLogger().info("Killing orphans");
            createStatement = connection.createStatement();
            createStatement.executeUpdate("DELETE FROM `" + tablePrefix + "experience` WHERE NOT EXISTS (SELECT * FROM `" + tablePrefix + "users` `u` WHERE `" + tablePrefix + "experience`.`user_id` = `u`.`id`)");
            createStatement.executeUpdate("DELETE FROM `" + tablePrefix + "huds` WHERE NOT EXISTS (SELECT * FROM `" + tablePrefix + "users` `u` WHERE `" + tablePrefix + "huds`.`user_id` = `u`.`id`)");
            createStatement.executeUpdate("DELETE FROM `" + tablePrefix + "cooldowns` WHERE NOT EXISTS (SELECT * FROM `" + tablePrefix + "users` `u` WHERE `" + tablePrefix + "cooldowns`.`user_id` = `u`.`id`)");
            createStatement.executeUpdate("DELETE FROM `" + tablePrefix + "skills` WHERE NOT EXISTS (SELECT * FROM `" + tablePrefix + "users` `u` WHERE `" + tablePrefix + "skills`.`user_id` = `u`.`id`)");
        } catch (SQLException ex) {
            printErrors(ex);
        } finally {
            tryClose(resultSet);
            tryClose(statement);
            tryClose(createStatement);
            tryClose(connection);
        }

    }

    private Connection getConnection(PoolIdentifier identifier) throws SQLException {
        Connection connection = null;
        switch (identifier) {
            case LOAD:
                connection = loadPool.getConnection();
                break;
            case MISC:
                connection = miscPool.getConnection();
                break;
            case SAVE:
                connection = savePool.getConnection();
                break;
        }
        if (connection == null) {
            throw new RuntimeException("getConnection() for " + identifier.name().toLowerCase(Locale.ENGLISH) + " pool timed out.  Increase max connections settings.");
        }
        return connection;
    }

    /**
     * Check database structure for necessary upgrades.
     *
     * @param upgrade Upgrade to attempt to apply
     */
    private void checkDatabaseStructure(Connection connection, UpgradeType upgrade) {
        /*if (!mcMMO.getUpgradeManager().shouldUpgrade(upgrade)) {
            mcMMO.p.debug("Skipping " + upgrade.name() + " upgrade (unneeded)");
            return;
        }*/

        Statement statement = null;

        try {
            statement = connection.createStatement();

            switch (upgrade) {
                case ADD_FISHING:
                    checkUpgradeAddFishing(statement);
                    break;

                case ADD_BLAST_MINING_COOLDOWN:
                    checkUpgradeAddBlastMiningCooldown(statement);
                    break;

                case ADD_SQL_INDEXES:
                    checkUpgradeAddSQLIndexes(statement);
                    break;

                case ADD_MOB_HEALTHBARS:
                    checkUpgradeAddMobHealthbars(statement);
                    break;

                case DROP_SQL_PARTY_NAMES:
                    checkUpgradeDropPartyNames(statement);
                    break;

                case DROP_SPOUT:
                    checkUpgradeDropSpout(statement);
                    break;

                case ADD_ALCHEMY:
                    checkUpgradeAddAlchemy(statement);
                    break;

                case ADD_UUIDS:
                    checkUpgradeAddUUIDs(statement);
                    return;

                case ADD_SCOREBOARD_TIPS:
                    checkUpgradeAddScoreboardTips(statement);
                    return;

                case DROP_NAME_UNIQUENESS:
                    checkNameUniqueness(statement);
                    return;

                case ADD_SKILL_TOTAL:
                    checkUpgradeSkillTotal(connection);
                    break;
                case ADD_UNIQUE_PLAYER_DATA:
                    checkUpgradeAddUniqueChimaeraWing(statement);
                    break;

                default:
                    break;

            }

            //mcMMO.getUpgradeManager().setUpgradeCompleted(upgrade);
        } catch (SQLException ex) {
            printErrors(ex);
        } finally {
            tryClose(statement);
        }
    }

    private void writeMissingRows(Connection connection, int id) {
        PreparedStatement statement = null;

        try {
            statement = connection.prepareStatement("INSERT IGNORE INTO " + tablePrefix + "experience (user_id) VALUES (?)");
            statement.setInt(1, id);
            statement.execute();
            statement.close();

            statement = connection.prepareStatement("INSERT IGNORE INTO " + tablePrefix + "skills (user_id) VALUES (?)");
            statement.setInt(1, id);
            statement.execute();
            statement.close();

            statement = connection.prepareStatement("INSERT IGNORE INTO " + tablePrefix + "cooldowns (user_id) VALUES (?)");
            statement.setInt(1, id);
            statement.execute();
            statement.close();

            statement = connection.prepareStatement("INSERT IGNORE INTO " + tablePrefix + "huds (user_id, mobhealthbar, scoreboardtips) VALUES (?, ?, ?)");
            statement.setInt(1, id);
            statement.setString(2, pluginRef.getConfigManager().getConfigMobs().getCombat().getHealthBars().getDisplayBarType().name());
            statement.setInt(3, 0);
            statement.execute();
            statement.close();
        } catch (SQLException ex) {
            printErrors(ex);
        } finally {
            tryClose(statement);
        }
    }

    private PlayerProfile loadFromResult(String playerName, ResultSet result) throws SQLException {
        Map<PrimarySkillType, Integer> skills = new EnumMap<>(PrimarySkillType.class); // Skill & Level
        Map<PrimarySkillType, Double> skillsXp = new EnumMap<>(PrimarySkillType.class); // Skill & XP
        Map<SuperAbilityType, Integer> skillsDATS = new EnumMap<>(SuperAbilityType.class); // Ability & Cooldown
        Map<UniqueDataType, Integer> uniqueData = new EnumMap<>(UniqueDataType.class); //Chimaera wing cooldown and other misc info
        MobHealthbarType mobHealthbarType;
        UUID uuid;
        int scoreboardTipsShown;

        final int OFFSET_SKILLS = 0; // TODO update these numbers when the query
        // changes (a new skill is added)
        final int OFFSET_XP = 13;
        final int OFFSET_DATS = 26;
        final int OFFSET_OTHER = 39;

        skills.put(PrimarySkillType.TAMING, result.getInt(OFFSET_SKILLS + 1));
        skills.put(PrimarySkillType.MINING, result.getInt(OFFSET_SKILLS + 2));
        skills.put(PrimarySkillType.REPAIR, result.getInt(OFFSET_SKILLS + 3));
        skills.put(PrimarySkillType.WOODCUTTING, result.getInt(OFFSET_SKILLS + 4));
        skills.put(PrimarySkillType.UNARMED, result.getInt(OFFSET_SKILLS + 5));
        skills.put(PrimarySkillType.HERBALISM, result.getInt(OFFSET_SKILLS + 6));
        skills.put(PrimarySkillType.EXCAVATION, result.getInt(OFFSET_SKILLS + 7));
        skills.put(PrimarySkillType.ARCHERY, result.getInt(OFFSET_SKILLS + 8));
        skills.put(PrimarySkillType.SWORDS, result.getInt(OFFSET_SKILLS + 9));
        skills.put(PrimarySkillType.AXES, result.getInt(OFFSET_SKILLS + 10));
        skills.put(PrimarySkillType.ACROBATICS, result.getInt(OFFSET_SKILLS + 11));
        skills.put(PrimarySkillType.FISHING, result.getInt(OFFSET_SKILLS + 12));
        skills.put(PrimarySkillType.ALCHEMY, result.getInt(OFFSET_SKILLS + 13));

        skillsXp.put(PrimarySkillType.TAMING, result.getDouble(OFFSET_XP + 1));
        skillsXp.put(PrimarySkillType.MINING, result.getDouble(OFFSET_XP + 2));
        skillsXp.put(PrimarySkillType.REPAIR, result.getDouble(OFFSET_XP + 3));
        skillsXp.put(PrimarySkillType.WOODCUTTING, result.getDouble(OFFSET_XP + 4));
        skillsXp.put(PrimarySkillType.UNARMED, result.getDouble(OFFSET_XP + 5));
        skillsXp.put(PrimarySkillType.HERBALISM, result.getDouble(OFFSET_XP + 6));
        skillsXp.put(PrimarySkillType.EXCAVATION, result.getDouble(OFFSET_XP + 7));
        skillsXp.put(PrimarySkillType.ARCHERY, result.getDouble(OFFSET_XP + 8));
        skillsXp.put(PrimarySkillType.SWORDS, result.getDouble(OFFSET_XP + 9));
        skillsXp.put(PrimarySkillType.AXES, result.getDouble(OFFSET_XP + 10));
        skillsXp.put(PrimarySkillType.ACROBATICS, result.getDouble(OFFSET_XP + 11));
        skillsXp.put(PrimarySkillType.FISHING, result.getDouble(OFFSET_XP + 12));
        skillsXp.put(PrimarySkillType.ALCHEMY, result.getDouble(OFFSET_XP + 13));

        // Taming - Unused - result.getInt(OFFSET_DATS + 1)
        skillsDATS.put(SuperAbilityType.SUPER_BREAKER, result.getInt(OFFSET_DATS + 2));
        // Repair - Unused - result.getInt(OFFSET_DATS + 3)
        skillsDATS.put(SuperAbilityType.TREE_FELLER, result.getInt(OFFSET_DATS + 4));
        skillsDATS.put(SuperAbilityType.BERSERK, result.getInt(OFFSET_DATS + 5));
        skillsDATS.put(SuperAbilityType.GREEN_TERRA, result.getInt(OFFSET_DATS + 6));
        skillsDATS.put(SuperAbilityType.GIGA_DRILL_BREAKER, result.getInt(OFFSET_DATS + 7));
        // Archery - Unused - result.getInt(OFFSET_DATS + 8)
        skillsDATS.put(SuperAbilityType.SERRATED_STRIKES, result.getInt(OFFSET_DATS + 9));
        skillsDATS.put(SuperAbilityType.SKULL_SPLITTER, result.getInt(OFFSET_DATS + 10));
        // Acrobatics - Unused - result.getInt(OFFSET_DATS + 11)
        skillsDATS.put(SuperAbilityType.BLAST_MINING, result.getInt(OFFSET_DATS + 12));
        uniqueData.put(UniqueDataType.CHIMAERA_WING_DATS, result.getInt(OFFSET_DATS + 13));


        try {
            mobHealthbarType = MobHealthbarType.valueOf(result.getString(OFFSET_OTHER + 1));
        } catch (Exception e) {
            mobHealthbarType = pluginRef.getConfigManager().getConfigMobs().getCombat().getHealthBars().getDisplayBarType();
        }

        try {
            scoreboardTipsShown = result.getInt(OFFSET_OTHER + 2);
        } catch (Exception e) {
            scoreboardTipsShown = 0;
        }

        try {
            uuid = UUID.fromString(result.getString(OFFSET_OTHER + 3));
        } catch (Exception e) {
            uuid = null;
        }

        return new PlayerProfile(pluginRef, playerName, uuid, skills, skillsXp, skillsDATS, mobHealthbarType, scoreboardTipsShown, uniqueData);
    }

    private void printErrors(SQLException ex) {
        if (debug) {
            ex.printStackTrace();
        }

        StackTraceElement element = ex.getStackTrace()[0];
        pluginRef.getLogger().severe("Location: " + element.getClassName() + " " + element.getMethodName() + " " + element.getLineNumber());
        pluginRef.getLogger().severe("SQLException: " + ex.getMessage());
        pluginRef.getLogger().severe("SQLState: " + ex.getSQLState());
        pluginRef.getLogger().severe("VendorError: " + ex.getErrorCode());
    }

    public DatabaseType getDatabaseType() {
        return DatabaseType.SQL;
    }

    private void checkNameUniqueness(final Statement statement) {
        ResultSet resultSet = null;
        try {
            resultSet = statement.executeQuery("SHOW INDEXES "
                    + "FROM `" + tablePrefix + "users` "
                    + "WHERE Column_name='user' "
                    + " AND NOT Non_unique");
            if (!resultSet.next()) {
                return;
            }
            resultSet.close();
            pluginRef.getLogger().info("Updating mcMMO MySQL tables to drop name uniqueness...");
            statement.execute("ALTER TABLE `" + tablePrefix + "users` "
                    + "DROP INDEX `user`,"
                    + "ADD INDEX `user` (`user`(20) ASC)");
        } catch (SQLException ex) {
            ex.printStackTrace();
        } finally {
            tryClose(resultSet);
        }
    }

    private void checkUpgradeAddAlchemy(final Statement statement) throws SQLException {
        try {
            statement.executeQuery("SELECT `alchemy` FROM `" + tablePrefix + "skills` LIMIT 1");
        } catch (SQLException ex) {
            pluginRef.getLogger().info("Updating mcMMO MySQL tables for Alchemy...");
            statement.executeUpdate("ALTER TABLE `" + tablePrefix + "skills` ADD `alchemy` int(10) NOT NULL DEFAULT '0'");
            statement.executeUpdate("ALTER TABLE `" + tablePrefix + "experience` ADD `alchemy` int(10) NOT NULL DEFAULT '0'");
        }
    }

    private void checkUpgradeAddBlastMiningCooldown(final Statement statement) throws SQLException {
        try {
            statement.executeQuery("SELECT `blast_mining` FROM `" + tablePrefix + "cooldowns` LIMIT 1");
        } catch (SQLException ex) {
            pluginRef.getLogger().info("Updating mcMMO MySQL tables for Blast Mining...");
            statement.executeUpdate("ALTER TABLE `" + tablePrefix + "cooldowns` ADD `blast_mining` int(32) NOT NULL DEFAULT '0'");
        }
    }

    private void checkUpgradeAddUniqueChimaeraWing(final Statement statement) throws SQLException {
        try {
            statement.executeQuery("SELECT `chimaera_wing` FROM `" + tablePrefix + "cooldowns` LIMIT 1");
        } catch (SQLException ex) {
            pluginRef.getLogger().info("Updating mcMMO MySQL tables for Chimaera Wing...");
            statement.executeUpdate("ALTER TABLE `" + tablePrefix + "cooldowns` ADD `chimaera_wing` int(32) NOT NULL DEFAULT '0'");
        }
    }

    private void checkUpgradeAddFishing(final Statement statement) throws SQLException {
        try {
            statement.executeQuery("SELECT `fishing` FROM `" + tablePrefix + "skills` LIMIT 1");
        } catch (SQLException ex) {
            pluginRef.getLogger().info("Updating mcMMO MySQL tables for Fishing...");
            statement.executeUpdate("ALTER TABLE `" + tablePrefix + "skills` ADD `fishing` int(10) NOT NULL DEFAULT '0'");
            statement.executeUpdate("ALTER TABLE `" + tablePrefix + "experience` ADD `fishing` int(10) NOT NULL DEFAULT '0'");
        }
    }

    private void checkUpgradeAddMobHealthbars(final Statement statement) throws SQLException {
        try {
            statement.executeQuery("SELECT `mobhealthbar` FROM `" + tablePrefix + "huds` LIMIT 1");
        } catch (SQLException ex) {
            pluginRef.getLogger().info("Updating mcMMO MySQL tables for mob healthbars...");
            statement.executeUpdate("ALTER TABLE `" + tablePrefix + "huds` ADD `mobhealthbar` varchar(50) NOT NULL DEFAULT '" + pluginRef.getConfigManager().getConfigMobs().getCombat().getHealthBars().getDisplayBarType() + "'");
        }
    }

    private void checkUpgradeAddScoreboardTips(final Statement statement) throws SQLException {
        try {
            statement.executeQuery("SELECT `scoreboardtips` FROM `" + tablePrefix + "huds` LIMIT 1");
        } catch (SQLException ex) {
            pluginRef.getLogger().info("Updating mcMMO MySQL tables for scoreboard tips...");
            statement.executeUpdate("ALTER TABLE `" + tablePrefix + "huds` ADD `scoreboardtips` int(10) NOT NULL DEFAULT '0' ;");
        }
    }

    private void checkUpgradeAddSQLIndexes(final Statement statement) {
        ResultSet resultSet = null;

        try {
            resultSet = statement.executeQuery("SHOW INDEX FROM `" + tablePrefix + "skills` WHERE `Key_name` LIKE 'idx\\_%'");
            resultSet.last();

            if (resultSet.getRow() != pluginRef.getSkillTools().NON_CHILD_SKILLS.size()) {
                pluginRef.getLogger().info("Indexing tables, this may take a while on larger databases");

                for (PrimarySkillType skill : pluginRef.getSkillTools().NON_CHILD_SKILLS) {
                    String skill_name = skill.name().toLowerCase(Locale.ENGLISH);

                    try {
                        statement.executeUpdate("ALTER TABLE `" + tablePrefix + "skills` ADD INDEX `idx_" + skill_name + "` (`" + skill_name + "`) USING BTREE");
                    } catch (SQLException ex) {
                        // Ignore
                    }
                }
            }
        } catch (SQLException ex) {
            printErrors(ex);
        } finally {
            tryClose(resultSet);
        }
    }

    private void checkUpgradeAddUUIDs(final Statement statement) {
        ResultSet resultSet = null;

        try {
            resultSet = statement.executeQuery("SELECT * FROM `" + tablePrefix + "users` LIMIT 1");

            ResultSetMetaData rsmeta = resultSet.getMetaData();
            boolean column_exists = false;

            for (int i = 1; i <= rsmeta.getColumnCount(); i++) {
                if (rsmeta.getColumnName(i).equalsIgnoreCase("uuid")) {
                    column_exists = true;
                    break;
                }
            }

            if (!column_exists) {
                pluginRef.getLogger().info("Adding UUIDs to mcMMO MySQL user table...");
                statement.executeUpdate("ALTER TABLE `" + tablePrefix + "users` ADD `uuid` varchar(36) NULL DEFAULT NULL");
                statement.executeUpdate("ALTER TABLE `" + tablePrefix + "users` ADD UNIQUE INDEX `uuid` (`uuid`) USING BTREE");
            }
        } catch (SQLException ex) {
            printErrors(ex);
        } finally {
            tryClose(resultSet);
        }

        //new GetUUIDUpdatesRequired().runTaskLaterAsynchronously(mcMMO.p, 100); // wait until after first purge
    }

    /*private class GetUUIDUpdatesRequired extends BukkitRunnable {
        public void run() {
            massUpdateLock.lock();
            List<String> names = new ArrayList<String>();
            Connection connection = null;
            Statement statement = null;
            ResultSet resultSet = null;
            try {
                try {
                    connection = miscPool.getConnection();
                    statement = connection.createStatement();
                    resultSet = statement.executeQuery("SELECT `user` FROM `" + tablePrefix + "users` WHERE `uuid` IS NULL");

                    while (resultSet.next()) {
                        names.add(resultSet.getString("user"));
                    }
                } catch (SQLException ex) {
                    printErrors(ex);
                } finally {
                    tryClose(resultSet);
                    tryClose(statement);
                    tryClose(connection);
                }

                if (!names.isEmpty()) {
                    new UUIDUpdateAsyncTask(mcMMO.p, names).run();
                }
            } finally {
                massUpdateLock.unlock();
            }
        }
    }*/

    private void checkUpgradeDropPartyNames(final Statement statement) {
        ResultSet resultSet = null;

        try {
            resultSet = statement.executeQuery("SELECT * FROM `" + tablePrefix + "users` LIMIT 1");

            ResultSetMetaData rsmeta = resultSet.getMetaData();
            boolean column_exists = false;

            for (int i = 1; i <= rsmeta.getColumnCount(); i++) {
                if (rsmeta.getColumnName(i).equalsIgnoreCase("party")) {
                    column_exists = true;
                    break;
                }
            }

            if (column_exists) {
                pluginRef.getLogger().info("Removing party name from users table...");
                statement.executeUpdate("ALTER TABLE `" + tablePrefix + "users` DROP COLUMN `party`");
            }
        } catch (SQLException ex) {
            printErrors(ex);
        } finally {
            tryClose(resultSet);
        }
    }

    private void checkUpgradeSkillTotal(final Connection connection) throws SQLException {
        ResultSet resultSet = null;
        Statement statement = null;

        try {
            connection.setAutoCommit(false);
            statement = connection.createStatement();
            resultSet = statement.executeQuery("SELECT * FROM `" + tablePrefix + "skills` LIMIT 1");

            ResultSetMetaData rsmeta = resultSet.getMetaData();
            boolean column_exists = false;

            for (int i = 1; i <= rsmeta.getColumnCount(); i++) {
                if (rsmeta.getColumnName(i).equalsIgnoreCase("total")) {
                    column_exists = true;
                    break;
                }
            }

            if (!column_exists) {
                pluginRef.getLogger().info("Adding skill total column to skills table...");
                statement.executeUpdate("ALTER TABLE `" + tablePrefix + "skills` ADD COLUMN `total` int NOT NULL DEFAULT '0'");
                statement.executeUpdate("UPDATE `" + tablePrefix + "skills` SET `total` = (taming+mining+woodcutting+repair+unarmed+herbalism+excavation+archery+swords+axes+acrobatics+fishing+alchemy)");
                statement.executeUpdate("ALTER TABLE `" + tablePrefix + "skills` ADD INDEX `idx_total` (`total`) USING BTREE");
                connection.commit();
            }
        } catch (SQLException ex) {
            printErrors(ex);
        } finally {
            connection.setAutoCommit(true);
            tryClose(resultSet);
            tryClose(statement);
        }
    }

    private void checkUpgradeDropSpout(final Statement statement) {
        ResultSet resultSet = null;

        try {
            resultSet = statement.executeQuery("SELECT * FROM `" + tablePrefix + "huds` LIMIT 1");

            ResultSetMetaData rsmeta = resultSet.getMetaData();
            boolean column_exists = false;

            for (int i = 1; i <= rsmeta.getColumnCount(); i++) {
                if (rsmeta.getColumnName(i).equalsIgnoreCase("hudtype")) {
                    column_exists = true;
                    break;
                }
            }

            if (column_exists) {
                pluginRef.getLogger().info("Removing Spout HUD type from huds table...");
                statement.executeUpdate("ALTER TABLE `" + tablePrefix + "huds` DROP COLUMN `hudtype`");
            }
        } catch (SQLException ex) {
            printErrors(ex);
        } finally {
            tryClose(resultSet);
        }
    }

    private int getUserID(final Connection connection, final String playerName, final UUID uuid) {
        if (uuid == null)
            return getUserIDByName(connection, playerName);

        if (cachedUserIDs.containsKey(uuid))
            return cachedUserIDs.get(uuid);

        ResultSet resultSet = null;
        PreparedStatement statement = null;

        try {
            statement = connection.prepareStatement("SELECT id, user FROM " + tablePrefix + "users WHERE uuid = ? OR (uuid IS NULL AND user = ?)");
            statement.setString(1, uuid.toString());
            statement.setString(2, playerName);
            resultSet = statement.executeQuery();

            if (resultSet.next()) {
                int id = resultSet.getInt("id");

                cachedUserIDs.put(uuid, id);

                return id;
            }
        } catch (SQLException ex) {
            printErrors(ex);
        } finally {
            tryClose(resultSet);
            tryClose(statement);
        }

        return -1;
    }

    private int getUserIDByName(final Connection connection, final String playerName) {
        ResultSet resultSet = null;
        PreparedStatement statement = null;

        try {
            statement = connection.prepareStatement("SELECT id, user FROM " + tablePrefix + "users WHERE user = ?");
            statement.setString(1, playerName);
            resultSet = statement.executeQuery();

            if (resultSet.next()) {
                int id = resultSet.getInt("id");

                return id;
            }
        } catch (SQLException ex) {
            printErrors(ex);
        } finally {
            tryClose(resultSet);
            tryClose(statement);
        }

        return -1;
    }

    private void tryClose(AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception e) {
                // Ignore
            }
        }
    }

    @Override
    public void onDisable() {
        pluginRef.debug("Releasing connection pool resource...");
        miscPool.close();
        loadPool.close();
        savePool.close();
    }

    public void resetMobHealthSettings() {
        PreparedStatement statement = null;
        Connection connection = null;

        try {
            connection = getConnection(PoolIdentifier.MISC);
            statement = connection.prepareStatement("UPDATE " + tablePrefix + "huds SET mobhealthbar = ?");
            statement.setString(1, pluginRef.getConfigManager().getConfigMobs().getCombat().getHealthBars().getDisplayBarType().toString());
            statement.executeUpdate();
        } catch (SQLException ex) {
            printErrors(ex);
        } finally {
            tryClose(statement);
            tryClose(connection);
        }
    }
}