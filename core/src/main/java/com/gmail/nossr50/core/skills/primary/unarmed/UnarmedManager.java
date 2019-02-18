package com.gmail.nossr50.core.skills.primary.unarmed;

import com.gmail.nossr50.core.config.AdvancedConfig;
import com.gmail.nossr50.core.data.UserManager;
import com.gmail.nossr50.core.datatypes.interactions.NotificationType;
import com.gmail.nossr50.core.datatypes.player.McMMOPlayer;
import com.gmail.nossr50.core.mcmmo.block.BlockState;
import com.gmail.nossr50.core.mcmmo.entity.Player;
import com.gmail.nossr50.core.mcmmo.item.ItemStack;
import com.gmail.nossr50.core.skills.*;
import com.gmail.nossr50.core.util.EventUtils;
import com.gmail.nossr50.core.util.ItemUtils;
import com.gmail.nossr50.core.util.Misc;
import com.gmail.nossr50.core.util.Permissions;
import com.gmail.nossr50.core.util.player.NotificationManager;
import com.gmail.nossr50.core.util.random.RandomChanceUtil;
import com.gmail.nossr50.core.util.skills.RankUtils;
import com.gmail.nossr50.core.util.skills.SkillActivationType;

public class UnarmedManager extends SkillManager {
    public UnarmedManager(McMMOPlayer mcMMOPlayer) {
        super(mcMMOPlayer, PrimarySkillType.UNARMED);
    }

    public boolean canActivateAbility() {
        return mcMMOPlayer.getToolPreparationMode(ToolType.FISTS) && Permissions.berserk(getPlayer());
    }

    public boolean canUseIronArm() {
        if (!RankUtils.hasUnlockedSubskill(getPlayer(), SubSkillType.UNARMED_IRON_ARM_STYLE))
            return false;

        return Permissions.isSubSkillEnabled(getPlayer(), SubSkillType.UNARMED_IRON_ARM_STYLE);
    }

    public boolean canUseBerserk() {
        return mcMMOPlayer.getAbilityMode(SuperAbilityType.BERSERK);
    }

    public boolean canDisarm(LivingEntity target) {
        if (!RankUtils.hasUnlockedSubskill(getPlayer(), SubSkillType.UNARMED_DISARM))
            return false;

        return target instanceof Player && ((Player) target).getInventory().getItemInMainHand().getType() != Material.AIR && Permissions.isSubSkillEnabled(getPlayer(), SubSkillType.UNARMED_DISARM);
    }

    public boolean canDeflect() {
        if (!RankUtils.hasUnlockedSubskill(getPlayer(), SubSkillType.UNARMED_ARROW_DEFLECT))
            return false;

        Player player = getPlayer();

        return ItemUtils.isUnarmed(player.getInventory().getItemInMainHand()) && Permissions.isSubSkillEnabled(getPlayer(), SubSkillType.UNARMED_ARROW_DEFLECT);
    }

    public boolean canUseBlockCracker() {
        if (!RankUtils.hasUnlockedSubskill(getPlayer(), SubSkillType.UNARMED_BLOCK_CRACKER))
            return false;

        return Permissions.isSubSkillEnabled(getPlayer(), SubSkillType.UNARMED_BLOCK_CRACKER);
    }

    public boolean blockCrackerCheck(BlockState blockState) {
        if (!RandomChanceUtil.isActivationSuccessful(SkillActivationType.ALWAYS_FIRES, SubSkillType.UNARMED_BLOCK_CRACKER, getPlayer())) {
            return false;
        }

        BlockData data = blockState.getBlockData();

        switch (blockState.getType()) {
            case Material.STONE_BRICKS:
                if (!Unarmed.blockCrackerSmoothBrick) {
                    return false;
                }

                blockState.setType(Material.CRACKED_STONE_BRICKS);
                return true;

            default:
                return false;
        }
    }

    /**
     * Check for disarm.
     *
     * @param defender The defending player
     */
    public void disarmCheck(Player defender) {
        if (RandomChanceUtil.isActivationSuccessful(SkillActivationType.RANDOM_LINEAR_100_SCALE_WITH_CAP, SubSkillType.UNARMED_DISARM, getPlayer()) && !hasIronGrip(defender)) {
            if (EventUtils.callDisarmEvent(defender).isCancelled()) {
                return;
            }

            Item item = Misc.dropItem(defender.getLocation(), defender.getInventory().getItemInMainHand());

            if (item != null && AdvancedConfig.getInstance().getDisarmProtected()) {
                item.setMetadata(mcMMO.disarmedItemKey, UserManager.getPlayer(defender).getPlayerMetadata());
            }

            defender.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
            NotificationManager.sendPlayerInformation(defender, NotificationType.SUBSKILL_MESSAGE, "Skills.Disarmed");
        }
    }

    /**
     * Check for arrow deflection.
     */
    public boolean deflectCheck() {
        if (RandomChanceUtil.isActivationSuccessful(SkillActivationType.RANDOM_LINEAR_100_SCALE_WITH_CAP, SubSkillType.UNARMED_ARROW_DEFLECT, getPlayer())) {
            NotificationManager.sendPlayerInformation(getPlayer(), NotificationType.SUBSKILL_MESSAGE, "Combat.ArrowDeflect");
            return true;
        }

        return false;
    }

    /**
     * Handle the effects of the Berserk ability
     *
     * @param damage The amount of damage initially dealt by the event
     */
    public double berserkDamage(double damage) {
        damage = (damage * Unarmed.berserkDamageModifier) - damage;

        return damage;
    }

    /**
     * Handle the effects of the Iron Arm ability
     */
    public double ironArm() {
        if (!RandomChanceUtil.isActivationSuccessful(SkillActivationType.ALWAYS_FIRES, SubSkillType.UNARMED_IRON_ARM_STYLE, getPlayer())) {
            return 0;
        }

        return getIronArmDamage();
    }

    public boolean isPunchingCooldownOver() {
        return (Unarmed.lastAttacked + Unarmed.attackInterval) <= System.currentTimeMillis();
    }

    public double getIronArmDamage() {
        return RankUtils.getRank(getPlayer(), SubSkillType.UNARMED_IRON_ARM_STYLE) * 2;
    }

    /**
     * Check Iron Grip ability success
     *
     * @param defender The defending player
     * @return true if the defender was not disarmed, false otherwise
     */
    private boolean hasIronGrip(Player defender) {
        if (!Misc.isNPCEntity(defender) && Permissions.isSubSkillEnabled(defender, SubSkillType.UNARMED_IRON_GRIP)
                && RandomChanceUtil.isActivationSuccessful(SkillActivationType.RANDOM_LINEAR_100_SCALE_WITH_CAP, SubSkillType.UNARMED_IRON_GRIP, getPlayer())) {
            NotificationManager.sendPlayerInformation(defender, NotificationType.SUBSKILL_MESSAGE, "Unarmed.Ability.IronGrip.Defender");
            NotificationManager.sendPlayerInformation(getPlayer(), NotificationType.SUBSKILL_MESSAGE, "Unarmed.Ability.IronGrip.Attacker");

            return true;
        }

        return false;
    }
}