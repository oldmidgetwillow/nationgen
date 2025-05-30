package nationGen.rostergeneration;

import com.elmokki.Generic;
import java.util.*;
import java.util.stream.Stream;
import nationGen.NationGen;
import nationGen.NationGenAssets;
import nationGen.chances.ChanceDistribution;
import nationGen.chances.EntityChances;
import nationGen.chances.ThemeInc;
import nationGen.entities.*;
import nationGen.items.CustomItem;
import nationGen.items.CustomItemGen;
import nationGen.items.Item;
import nationGen.misc.Arg;
import nationGen.misc.ChanceIncHandler;
import nationGen.misc.Command;
import nationGen.misc.ItemSet;
import nationGen.misc.Utils;
import nationGen.nation.Nation;
import nationGen.rostergeneration.montagtemplates.SacredMontagTemplate;
import nationGen.rostergeneration.powermanagers.StatUpgradeManager;
import nationGen.rostergeneration.powermanagers.UnitPower;
import nationGen.units.Unit;

public class SacredGenerator extends TroopGenerator {

  private static final List<String> SLOTS = List.of(
    "armor",
    "weapon",
    "offhand",
    "bonusweapon",
    "helmet",
    "cloakb",
    "hair"
  );

  private ItemSet usedItems = new ItemSet();
  private CustomItemGen customItemGen;

  public SacredGenerator(NationGen g, Nation n, NationGenAssets assets) {
    super(g, n, assets, "sacgen");
    customItemGen = new CustomItemGen(nation);

    this.nation.selectTroops()
      .forEach(u ->
        SLOTS.forEach(slot -> {
          Item i = u.getSlot(slot);
          if (i != null) {
            if (!usedItems.contains(i)) usedItems.add(i);
          }
        })
      );
  }

  private void addEpicness(Unit unit, boolean isSacred, int power) {
    // Create a power/stat upgrade budget for this unit
    UnitPower unitPower = new UnitPower(power, 1);

    // Vary the base unit power and stat upgrade budget with some RNG to diversify resulting units
    unitPower.rollChanceOfStatUpgradeBudgetChange(random, 0.25, 1);
    unitPower.rollChanceOfStatUpgradeBudgetChange(random, 0.25, 1);
    unitPower.rollChanceOfBudgetChange(random, 0.07, 1, -2);

    // Scale some basic stats (morale, MR) of unit to the raw power.
    // High power units should not fold like paper tigers. These stat
    // changes are not spent from the stat upgrade budget; they are free.
    scaleStatsToPower(unit, unitPower.power);

    // Roll a magic weapon and discount its cost from power.
    // Note this is not guaranteed; the roll might fail.
    if (unitPower.powerLeft > 0) {
      unitPower.powerLeft -=
      rollMagicWeapon(unit, isSacred, unitPower.powerLeft, 0.15);
    }

    // If we still have power, determine how much of it will be used in stat upgrades
    if (unitPower.powerLeft > 0) {
      // Allocate from 0 up to power - 1 into stat upgrades instead
      int powerToStatUpgrades = random.nextInt(unitPower.powerLeft);
      unitPower.shiftPowerToStatUpgradeBudget(
        -powerToStatUpgrades,
        powerToStatUpgrades
      );
    }

    // Get a list of all the valid filters that this unit can get added
    List<Filter> extraFilterOptions = getValidFilterOptions(unit, isSacred);

    // Add up to 15 power worth of filters (arbitrary, but that's 5 filters of power 3).
    // Most of the time there won't be anywhere near enough sacredpower for this, but
    // this could easily happen with the Batshit Insane sacred power setting.
    int maxFilterPowerTotal = 15;
    int filterPowerAdded = 0;
    int loopSafety = 0;

    // While we still have power budget and we haven't reached the maximum filter power...
    while (unitPower.powerLeft > 0 && filterPowerAdded < maxFilterPowerTotal) {
      loopSafety++;
      if (loopSafety > 50) {
        break;
      }

      // No more options left to use for this sacred, break early
      if (extraFilterOptions.size() == 0) {
        break;
      }

      // Select a valid filter for this unit with a power up to however much we have left to spend
      Filter filterToAdd = selectValidRandomFilter(
        unit,
        extraFilterOptions,
        unitPower.powerLeft
      );

      // If we found a valid filter...
      if (filterToAdd != null) {
        // ...remove it and related ones from the options
        chandler.removeRelated(filterToAdd, extraFilterOptions);

        // ...add it to the unit
        unit.appliedFilters.add(filterToAdd);
        unit.tags.addAll(filterToAdd.tags);

        // ...pay the filter cost with the current power budget. Note that if the filter
        // is a negative one (i.e. extra supply cost), this actually increases our budget.
        unitPower.powerLeft -= filterToAdd.power;
        filterPowerAdded += filterToAdd.power;
      }
    }

    // Add remaining unspent power to stat upgrade budget
    unitPower.shiftPowerToStatUpgradeBudget(
      -unitPower.powerLeft,
      unitPower.powerLeft
    );

    // Scale stat upgrade budget to a more suitable size for stat prices
    unitPower.scaleStatUpgradeBudget(3);

    // For variation, scale the final stat upgrade budget between x0.75 to x1.25
    double finalStatUpgradeBudgetFactor = 0.75 + random.nextDouble() * 0.5;
    unitPower.scaleStatUpgradeBudget(finalStatUpgradeBudgetFactor);

    // Spend all the stat upgrade budget
    spendStatUpgradeBudget(unit, unitPower.statUpgradeBudgetLeft);
  }

  private List<Filter> getValidFilterOptions(Unit unit, boolean isSacred) {
    List<Filter> filters;

    if (isSacred) {
      String[] defaults = {
        "default_sacredfilters",
        "default_sacredfilters_shapeshift",
      };
      filters = ChanceIncHandler.retrieveFilters(
        "sacredfilters",
        defaults,
        assets.filters,
        unit.pose,
        unit.race
      );
    } else {
      String[] defaults = {
        "default_elitefilters",
        "default_elitefilters_shapeshift",
      };
      filters = ChanceIncHandler.retrieveFilters(
        "elitefilters",
        defaults,
        assets.filters,
        unit.pose,
        unit.race
      );
    }

    filters = ChanceIncHandler.getValidUnitFilters(filters, unit);
    filters.retainAll(chandler.handleChanceIncs(unit, filters).getPossible());
    return filters;
  }

  private void scaleStatsToPower(Unit unit, int power) {
    int baseMr = unit.getCommandValue("#mr", 10);
    int maxMrBonus = 4;
    int maxMoraleBonus = 6;
    int diminishingReturnsMrThreshold = 11;
    int diminishingReturnsMoraleThreshold = 14;

    // Determine magic resistance scaling. Maximum raw addition is 4
    int mrBonus = (int) Math.min(Math.round(power / 2.0), maxMrBonus);

    // Any mr above 11 is halved: 13 becomes 12, 15 becomes 13 and so on
    if (baseMr > diminishingReturnsMrThreshold) {
      mrBonus -= (baseMr - diminishingReturnsMrThreshold) / 2;
    }

    unit.commands.add(Command.args("#mr", "+" + mrBonus));

    // Determine morale
    int baseMorale = unit.getCommandValue("#mor", 10);

    // 50% of power + 50% of 0 to power + 2
    int moraleBonus = (int) Math.round(
      Math.min(
        0.5 * (double) power + 0.5 * (double) random.nextInt(power + 1) + 2,
        maxMoraleBonus
      )
    );

    // At 18 morale, 0% chance
    // At 16 morale, 6.25% chance
    // At 14 morale, 12.5% chance
    // At 12 morale, 18.75% chance
    // At 10 morale, 25% chance
    if (
      random.nextDouble() < (1 - (baseMorale + moraleBonus - 10) / 8.0) / 4.0
    ) moraleBonus++;
    if (
      random.nextDouble() < (1 - (baseMorale + moraleBonus - 10) / 8.0) / 4.0
    ) moraleBonus++;

    // Morale above 16 gets halved, ie 18 -> 17, 20 -> 18 and so on.
    if (baseMorale + moraleBonus > diminishingReturnsMoraleThreshold) {
      moraleBonus -=
      (baseMorale + moraleBonus - diminishingReturnsMoraleThreshold) / 2;
    }

    // Add morale bonus
    unit.commands.add(Command.args("#mor", "+" + moraleBonus));
  }

  // Roll a chance to see if this unit will get a magic weapon added to it
  private int rollMagicWeapon(
    Unit unit,
    boolean isSacred,
    int power,
    double baseChance
  ) {
    double roll = random.nextDouble();
    double magicWeaponChance = baseChance + power * 0.03;

    // Cost is 1 to 6. This is the power that the magic weapon will have as well
    int cost = 1 + random.nextInt(Math.min(6, power));

    // If unit is sacred and rand is < 0.2 + 0.03 of power (i.e. 44% for power 8)
    boolean rolledMagicWeapon = isSacred && roll < magicWeaponChance;

    // Find any weapon, bonusweapon or offhand in this unit that is guaranteed magic
    boolean hasGuaranteedMagicWeapon = Stream.of(
      "weapon",
      "bonusweapon",
      "offhand"
    )
      .map(unit::getSlot)
      .filter(Objects::nonNull)
      .filter(i -> !i.armor)
      .anyMatch(i -> i.tags.containsName("guaranteedmagic"));

    // If unit is meant to get a magic weapon already, or we lucked into it,
    // make sure it gets one and return its cost so it can be spent from the budget
    if (hasGuaranteedMagicWeapon == true || rolledMagicWeapon == true) {
      // Magic weapon is added later since no weapons exist when this is run
      unit.tags.add("NEEDSMAGICWEAPON", cost);
      return cost;
    } else return 0;
  }

  // Select a single filter close to a given power for this unit and return it
  private Filter selectValidRandomFilter(
    Unit unit,
    List<Filter> filterChoices,
    int upToPower
  ) {
    List<Filter> choices;

    if (filterChoices.size() == 0) {
      return null;
    }

    // Find the filter with the highest power in the list above
    Optional<Filter> possibleHighestPowerFilter = filterChoices
      .stream()
      .max(Comparator.comparing(Filter::getPower));

    // No filter found after the comparison
    if (possibleHighestPowerFilter.isPresent() == false) {
      return null;
    }

    int highestFilterPower = (int) possibleHighestPowerFilter.get().power;
    int powerCap = Math.min(highestFilterPower, upToPower);

    // If we're unlucky (5%), force a negative filter onto this unit instead
    if (random.nextDouble() < 0.05) {
      choices = ChanceIncHandler.getFiltersWithPower(
        Integer.MIN_VALUE,
        -1,
        filterChoices
      );
    } else {
      choices = ChanceIncHandler.getFiltersWithPower(
        0,
        powerCap,
        filterChoices
      );
    }

    // Boil down choices to valid ones for this unit
    choices = ChanceIncHandler.getValidUnitFilters(choices, unit);

    if (choices.size() > 0) {
      // Select one filter at random from the list of choices created above
      Filter chosenFilter = chandler
        .handleChanceIncs(unit, choices)
        .getRandom(random);

      if (chosenFilter != null && ChanceIncHandler.canAdd(unit, chosenFilter)) {
        return chosenFilter;
      }
    }

    return null;
  }

  // Spend all the budget for stat upgrades for this unit
  private void spendStatUpgradeBudget(Unit unit, int budget) {
    StatUpgradeManager statUpgradeManager = new StatUpgradeManager(
      nationGen,
      unit,
      budget,
      random
    );

    // While there's budget left for stat increases, keep buying stat upgrades
    while (statUpgradeManager.cannotAffordMoreUpgrades == false) {
      statUpgradeManager.buyStatUpgrade();
    }
  }

  private void customizeWeaponry(Unit unit, int power) {
    List<MagicItem> magicItems = ChanceIncHandler.retrieveFilters(
      "magicitems",
      "defaultprimary",
      assets.magicitems,
      unit.pose,
      unit.race
    );

    // Retrieve unit's weapons
    Item mainWeapon = unit.getSlot("weapon");
    Item bonusWeapon = unit.getSlot("bonusweapon");
    Item offhandWeapon = unit.getSlot("offhand");

    // Check if main weapon should get customized
    if (getsCustomWeapon(unit, mainWeapon, 1, false) == true) {
      // Customize main weapon and get the cost of it
      int powerCost = customizeWeapon(unit, "weapon", power, magicItems);

      // Spend the power cost. Only relevant for whether bonus or offhand is also customized
      power -= powerCost;
    }

    // Check if bonus weapon should get customized
    if (getsCustomWeapon(unit, bonusWeapon, 0.25, power > 1) == true) {
      // Customize bonus weapon and get the cost of it
      int powerCost = customizeWeapon(unit, "bonusweapon", power, magicItems);

      // Spend the power cost. Only relevant for whether offhand is also customized
      power -= powerCost;
    }

    // Only offhand weapons get enhanced for now
    if (offhandWeapon != null && offhandWeapon.isArmor() == true) {
      return;
    }

    // Check if offhand weapon should get customized
    if (getsCustomWeapon(unit, offhandWeapon, 0.25, power > 1) == true) {
      // Customize offhand weapon
      customizeWeapon(unit, "offhand", power, magicItems);
    }
  }

  private Boolean getsCustomWeapon(Unit unit, Item weapon, double chance, Boolean isGuaranteed) {
    if (weapon == null) {
      return false;
    }

    Boolean canWeaponBeEpic = weapon.tags.containsName("notepic") == false;
    Boolean isGuaranteedMagicWeapon = weapon.tags.containsName("guaranteedmagic");
    double roll = random.nextDouble();
    return canWeaponBeEpic && (
      isGuaranteedMagicWeapon ||
      isGuaranteed ||
      roll <= chance
    );
  }

  private int customizeWeapon(Unit unit, String slot, int power, List<MagicItem> magicItems) {
    double powerUpChances = 1 - random.nextDouble();
    Item weapon = unit.getSlot(slot);

    if (weapon == null) {
      return 0;
    }

    Optional<CustomItem> possibleWeapon =
      this.customItemGen.customizeItem(
        unit,
        weapon,
        power,
        // TODO: tie this powerUpChance to something, rather than being fully random?
        powerUpChances,
        magicItems
      );

    if (possibleWeapon.isPresent() == false) {
      return 0;
    }

    CustomItem customWeapon = possibleWeapon.get();
    double powerCost = (customWeapon.magicItem != null) ? customWeapon.magicItem.power : 1;
    unit.setSlot(slot, customWeapon);
    return (int)powerCost;
  }

  private void addInitialFilters(Unit u) {
    unitGen.addFreeTemplateFilters(u);
    addTemplateFilter(u, "sacredtemplates", "default_sacredtemplates");
    double nextDouble = random.nextDouble();

    if (nextDouble < 0.1) {
      addTemplateFilter(u, "sacredtemplates", "default_sacredtemplates");
    }
  }

  private boolean canBeSacred(boolean sacred, Race race) {
    boolean canBeSacred = false;
    String[] roles = { "infantry", "mounted", "chariot", "ranged" };
    for (String r : roles) {
      if (
        race.hasRole(r) || (race.hasRole("sacred " + r) && sacred)
      ) canBeSacred = true;
    }

    return canBeSacred;
  }

  public Unit generateUnit(boolean sacred, int power, boolean isFirstSacred) {
    return generateUnit(sacred, power, null, isFirstSacred);
  }

  public Unit generateUnit(
    boolean sacred,
    int power,
    Race race,
    boolean isFirstSacred
  ) {
    if (race == null) race = getRace(sacred);

    Pose p = getPose(sacred, power, race, isFirstSacred);

    double epicchance = random.nextDouble() * 0.5 + power * 0.25 + 0.25;

    Unit u = this.unitGen.generateUnit(race, p);

    Optional<Integer> innateSacredPower = Generic.getAllUnitTags(u).getInt(
      "innate_sacred_power"
    );
    if (innateSacredPower.isPresent()) {
      power -= innateSacredPower.get();

      if (sacred) {
        power = Math.max(1, power);
      }

      else {
        power = Math.max(0, power);
      }
    }

    u = getSacredUnit(u, power, sacred, epicchance);

    if (unitGen.hasMontagPose(u)) {
      SacredMontagTemplate template = new SacredMontagTemplate(
        nation,
        nationGen,
        assets
      );
      template.power = power;
      template.sacred = sacred;
      unitGen.handleMontagUnits(u, template, "montagsacreds");
      u.caponly = true;
    }
    
    else {
      addSacredCostMultipliers(u, power);
      determineIfCapOnly(u, isFirstSacred);
    }

    return u;
  }

  private Race getRace(boolean sacred) {
    Race primaryRace = nation.races.get(0);
    boolean foreigners = Stream.concat(
      nation.comlists.get("mages").stream(),
      nation.selectTroops()
    ).anyMatch(u -> u.race != primaryRace);

    double bonussecchance = 1;
    bonussecchance +=
    primaryRace.tags.getDouble("secondaryracesacredmod").orElse(0D);
    bonussecchance -=
    nation.races.get(1).tags.getDouble("primaryracesacredmod").orElse(0D);

    Race race = primaryRace;
    if (foreigners || random.nextDouble() < 0.05 * bonussecchance) {
      if (
        random.nextDouble() < 0.2 * bonussecchance &&
        nation.races.get(1) != null &&
        canBeSacred(sacred, nation.races.get(1))
      ) race = nation.races.get(1);
    } else if (!canBeSacred(sacred, nation.races.get(0))) {
      race = nation.races.get(1);
    }

    return race;
  }

  public Pose getPose(
    boolean sacred,
    int power,
    Race race,
    boolean isFirstSacred
  ) {
    // Store chances of a pose getting picked over others
    ChanceDistribution<String> poseChances = new ChanceDistribution<>();

    // Store viable poses. This is an aid to be able to run handleChanceIncs() and filter based on those
    List<Pose> possiblePoses = new ArrayList<>();

    // The different unit roles and their national chances; modified by the unit's power
    ChanceDistribution<String> roles = new ChanceDistribution<>();
    String toGet = "sacred";

    // Calculate a chance for the infantry role based on the racial chance and the power this unit rolled
    if (race.hasSpecialRole("infantry", sacred)) {
      roles.setChance(
        "infantry",
        race.tags.getDouble(toGet + "infantrychance").orElse(1.0) + power * 0.1
      );
    }

    // Calculate a chance for the mounted role based on the racial chance and the power this unit rolled
    if (race.hasSpecialRole("mounted", sacred)) {
      roles.setChance(
        "mounted",
        race.tags.getDouble(toGet + "mountedchance").orElse(0.25) + power * 0.15
      );
    }

    // Calculate a chance for the chariot role based on the racial chance and the power this unit rolled
    if (race.hasSpecialRole("chariot", sacred)) {
      roles.setChance(
        "chariot",
        race.tags.getDouble(toGet + "chariotchance").orElse(0.05) + power * 0.1
      );
    }

    // Calculate a chance for the ranged role based on the racial chance and the power this unit rolled
    // Note that the first sacred of a nation should very rarely be ranged if any others are available
    if (race.hasSpecialRole("ranged", sacred) && !isFirstSacred) {
      roles.setChance(
        "ranged",
        race.tags.getDouble(toGet + "rangedchance").orElse(0.125) + power * 0.05
      );
    } else if (race.hasSpecialRole("ranged", sacred)) {
      roles.setChance("ranged", 0.05);
    }

    // Loop through the roles, eliminating them from the list as we go,
    // and pick a pose with this role that qualifies as elite or sacred
    while (roles.hasPossible()) {
      String role = roles.getRandom(this.random);
      double roleChance = roles.getChance(role);
      roles.eliminate(role);

      // Iterate through all race poses
      for (Pose p : race.poses) {
        boolean isSacred = false;
        String poseId = Integer.toString(p.hashCode());

        // If the pose contains a role with "sacred", make this a sacred
        for (String trole : p.roles) {
          if (
          (trole.contains("sacred") && sacred) ||
          (p.tags.containsName("sacred") && sacred) ||
          (trole.contains("elite") && !sacred) ||
          (p.tags.containsName("elite") && !sacred)
          ) {
            isSacred = true;
          }
        }

        // If this will be a sacred and the pose contains the role in question,
        // add it as a suitable pose with the role chance as its weight
        if (
          isSacred &&
          ((sacred && p.roles.contains("sacred " + role)) ||
            (!sacred && p.roles.contains("elite " + role)) ||
            p.roles.contains(role))
        ) {
          poseChances.setChance(poseId, roleChance);
          possiblePoses.add(p);
        }
        
        // If this will be an elite and the pose contains the role in question,
        // add it as a suitable pose with the role chance as its weight
        else if (
          ((race.tags.containsName("all_troops_sacred") && sacred) ||
            ((race.tags.containsName("all_troops_elite") && !sacred))) &&
          p.roles.contains(role)
        ) {
          poseChances.setChance(poseId, roleChance);
          possiblePoses.add(p);
        }
      }
    }

    if (poseChances.isEmpty()) {
      throw new IllegalStateException(
        "No " +
        (sacred ? "sacred" : "elite") +
        " poses were found for " +
        race.name +
        ". Consider adding #all_troops_sacred or #all_troops_elite to race file to use normal poses."
      );
    }

    // Handle the inherent chance definitions for the selected poses.
    // This is important! I.e. some foulspawn poses are just for montags,
    // not meant to generate as standalone units of their own. This handles
    // the #basechance, #chanceinc, #themeinc tags in the pose definitions.
    EntityChances<Pose> poseChanceIncs = chandler.handleChanceIncs(race, possiblePoses);
    ChanceDistribution<String> filteredChances = new ChanceDistribution<>();

    // For each of the selected poses...
    possiblePoses.forEach(p -> {
      // ...get the role chance and calculated base/chanceinc chances
      String poseId = Integer.toString(p.hashCode());
      double poseChance = poseChances.getChance(poseId);
      double chanceIncModifiedChance = poseChanceIncs.getChance(p);

      // If either of those is 0, don't add the pose. For example, some poses
      // are not meant to be picked as a unit, and only used to generate heroes
      if (poseChance > 0 && chanceIncModifiedChance > 0) {
        // If they should be picked, just average the two chances as the final chance
        double averageChances = (poseChance + chanceIncModifiedChance) * 0.5;
        filteredChances.setChance(poseId, averageChances);
      }
    });

    // Get a random pose based on the final chances
    String chosenPoseHashCode = filteredChances.getRandom(this.random);

    // We have to do some yoyo here to get the actual pose object using the pose
    // hashcode. This is because of having to use two separate data structures
    Optional<Pose> chosenPose = race.poses
      .stream()
      .filter(p -> (Integer.toString(p.hashCode())).equals(chosenPoseHashCode))
      .findFirst();

    if (chosenPose.isPresent() == false) {
      throw new IllegalStateException(
        "After chanceincs were handled, no " +
        (sacred ? "sacred" : "elite") +
        " poses were found for " +
        race.name +
        ". Consider adding #all_troops_sacred or #all_troops_elite to race file to use normal poses."
      );
    }

    return chosenPose.get();
  }

  public void addSacredCostMultipliers(Unit u, int power) {
    double total = 1;
    List<Double> multipliers = Generic.getAllUnitTags(u).getAllDoubles(
      "sacredcostmulti"
    );

    for (Double multi : multipliers) {
      total *= multi;
    }

    u.commands.add(Command.args("#gcost", "*" + total));
  }

  /**
   * Determine whether this unit will become caponly or not
   * @param u
   * @param power
   */
  public void determineIfCapOnly(Unit u, boolean isFirstSacred) {
    double unitRating = calculateUnitRating(u);

    // If this is not the nation's first sacred, the chances that
    // it's cap only will be lower by this factor
    double additionalSacredBonus = (isFirstSacred == false) ? 0.9 : 1;

    // The highest capOnlyChance for the unit will apply if one is defined
    double highestCapOnlyChance = Generic.getAllUnitTags(u)
      .streamAllValues("caponlychance")
      .map(Arg::getDouble)
      .max(Double::compareTo)
      .orElse(0D);

    u.survivability = calculateSurvivability(u);

    // Weigh survivability of the unit higher than its filter power rating for rec-everywhere chances
    double ratingAndSurvivabilityWeights = unitRating * u.survivability;

    // TODO: change the constant 0.9 to depend on a setting to modify how common cap-only units will be
    double adjustedCapOnlyChance = (ratingAndSurvivabilityWeights * additionalSacredBonus) * 0.9;

    u.capOnlyChance = Math.max(
      highestCapOnlyChance,
      Math.min(0.97, adjustedCapOnlyChance)
    );

    if (random.nextDouble() < u.capOnlyChance) {
      u.caponly = true;
    }
  }

  /**
   * Calculates a loose measure of a unit's power in relation
   * to the filters that have been applied to it. Note that this
   * only counts filters that have been added on top, not base unit
   * properties. A unit that rolled many extra filters has a higher rating.
   * @param u
   * @param power
   * @return the unit's rating
   */
  public double calculateUnitRating(Unit unit) {
    float amntOfBadFilterPower = 0;
    float amntOfGoodFilterPower = 0;
    int numOfBadFilters = 0;
    int numOfGoodFilters = 0;
    double badFilterRating = 0;
    double goodFilterRating = 0;
    
    float cumulativeBadFilterScaling = 0.1f;
    float cumulativeGoodFilterScaling = 0.1f;
    float extraBadFilterRatingFromAccumulation = 0;
    float extraGoodFilterRatingFromAccumulation = 0;

    
    List<Double> sacredRatingMultiplierTags = Generic.getAllUnitTags(
      unit
    ).getAllDoubles("sacredratingmulti");

    for (Filter f : unit.appliedFilters) {
      if (f.power <= -1) {
        // Use the sum of squares of multiple filters
        amntOfBadFilterPower += Math.pow(f.power, 2);
        // Every filter after the first starts adding cumulatively more value to the rating
        extraBadFilterRatingFromAccumulation += numOfBadFilters * cumulativeBadFilterScaling;
        numOfBadFilters++;
      }
      
      else if (f.power >= 1) {
        // Use the sum of squares of multiple filters
        amntOfGoodFilterPower += Math.pow(f.power, 2);
        // Every filter after the first starts adding cumulatively more value to the rating
        extraGoodFilterRatingFromAccumulation += numOfGoodFilters * cumulativeGoodFilterScaling;
        numOfGoodFilters++;
      }
    }

    // Scale down the sum of squares with a root. This makes higher power filters have more weight than lower powers.
    // Scale them down as well by diving with a constant after the root just to bring it to a better size.
    goodFilterRating = (Math.cbrt(amntOfGoodFilterPower) / 1.5) + extraGoodFilterRatingFromAccumulation;
    badFilterRating = (Math.cbrt(amntOfBadFilterPower) / 1.5) + extraBadFilterRatingFromAccumulation;

    for (Double multi : sacredRatingMultiplierTags) {
      goodFilterRating *= multi;
    }

    return goodFilterRating - Math.abs(badFilterRating);
  }

  /**
   * Calculates a survivability score where 0 is extremely flimsy (like a pygmy) and anything
   * close to 1 and beyond one is "best survivability of its class" (i.e. high hp giants, high
   * defence elves, full plate humans, etc.).
   * @param u
   */
  public float calculateSurvivability(Unit u) {
    // Unit's survivability stats
    int hp = u.getCommandValue("#hp", 10);
    int parry = u.getParry();
    int def = u.getTotalDef() - parry;
    int shieldProt = u.getShieldProt();
    float finalProt = u.getTotalProt();
    int mr = u.getCommandValue("#mr", 10);

    // Least survivable values taken from a base pygmy template.
    // If you have these, your survivability is basically 0.
    int leastSurvivableHp = 4;
    int leastSurvivableDef = 7;
    int leastSurvivableProt = 0;
    int leastSurvivableMr = 7;

    // Most survivable values taken from several units.
    // If you have these, your survivability is 1, towards the top.
    int mostSurvivableHp = 69;
    int mostSurvivableDef = 18;
    int mostSurvivableProt = 22;
    int mostSurvivableMr = 18;

    // Only count a percentage of the shield's parry as defense, depending
    // on how close its protection is to the best survivable protection
    def = def + (int)(Math.min(1, (float)shieldProt / (float)mostSurvivableProt) * parry);

    // Normalize the above scores to a range of 0 to 1
    float normalizedHp = Utils.normalize(
      hp,
      leastSurvivableHp,
      mostSurvivableHp
    );
    float normalizedDef = Utils.normalize(
      def,
      leastSurvivableDef,
      mostSurvivableDef
    );
    float normalizedProt = Utils.normalize(
      finalProt,
      leastSurvivableProt,
      mostSurvivableProt
    );
    float normalizedMr = Utils.normalize(
      mr,
      leastSurvivableMr,
      mostSurvivableMr
    );
    List<Command> unitCommands = u.getCommands();
    Boolean isUndeadOrDemon = unitCommands
      .stream()
      .filter(c -> c.command.equals("#undead") || c.command.equals("#demon"))
      .findAny()
      .isPresent();

    // Find the best survival stat of this unit
    float bestStat = Math.max(Math.max(normalizedHp, normalizedDef), normalizedProt);

    // Calculate the sum with the best survival stat getting twice the weight. The logic here is that
    // a unit generally only needs one of these stats being good to be broadly sturdy (always depends vs. what)
    float sumWithBestWeight = (bestStat * 2) + (normalizedHp + normalizedDef + normalizedProt - bestStat) / 2;

    // Calculate the weighted average to get a weighted, normalized survivability score between 0 and 1
    float averageSurvivability = sumWithBestWeight / 3;

    // If undead or demon, recalculate the average taking MR into account
    if (isUndeadOrDemon == true) {
      averageSurvivability = ((averageSurvivability * 3) + normalizedMr) / 4;
    }

    // Now count additional traits that may help survival
    float survivability = averageSurvivability;

    for (Command c : u.getCommands()) {
      if (c.command.equals("#regen")) {
        survivability += 0.05 * Math.ceil(hp * 0.1);
      }

      if (c.command.equals("#awe") || c.command.equals("#sunawe")) {
        survivability += 0.1;
      }

      if (c.command.equals("#invuln")) {
        survivability += 0.1;
      }

      if (c.command.equals("#illusion")) {
        survivability += 0.1;
      }

      if (c.command.equals("#ethereal")) {
        survivability += 0.15;
      }

      if (c.command.equals("#glamour")) {
        survivability += 0.15;
      }

      if (c.command.equals("#entangle")) {
        survivability += 0.15;
      }

      if (c.command.equals("#secondshape")) {
        survivability += 0.05;
      }

      if (c.command.equals("#firstshape")) {
        survivability += 0.05;
      }

      if (c.command.equals("#overcharged")) {
        Integer overchargedAmnt = Integer.parseInt(c.args.get(0).get());
        survivability += overchargedAmnt * 0.05;
      }

      if (c.command.equals("#unsurr")) {
        Integer unsurroundableAmnt = Integer.parseInt(c.args.get(0).get());
        survivability += unsurroundableAmnt * 0.015;
      }
    }

    return survivability;
  }

  public Unit getSacredUnit(
    Unit u,
    int power,
    boolean sacred,
    double epicchance
  ) {
    Race race = u.race;

    //Unit u = this.unitGen.generateUnit(race, p);
    String role = "";

    // Filters
    this.addInitialFilters(u);

    // Add more stat upgrades, magic weapon, traits based on sacred's power
    if (!unitGen.hasMontagPose(u)) {
      this.addEpicness(u, sacred, power);
    }

    // Equip
    for (String r : u.pose.roles) if (
      r.contains("infantry") || r.contains("sacred infantry")
    ) role = "infantry";
    else if (r.contains("mounted") || r.contains("sacred mounted")) role =
      "mounted";
    else if (r.contains("chariot") || r.contains("sacred chariot")) role =
      "chariot";
    else if (r.contains("ranged") || r.contains("sacred ranged")) role =
      "ranged";

    if (role.equals("")) {
      System.out.println(
        "WARNING: No role in some pose of " +
        race.name +
        ", possible pose name: " +
        u.pose.name +
        ", possible other roles: " +
        u.pose.roles
      );
      return null;
    }

    // Add a sacred generic filter
    Filter tf = new Filter(nationGen);

    // Don't count this filter towards unit power calculations
    tf.power = 0;

    if (sacred) tf.name = Generic.capitalize(role) + " sacred";
    else tf.name = Generic.capitalize(role) + " elite";

    tf.tags.addName("not_montag_inheritable");

    if (sacred) {
      tf.themeincs.add(
        ThemeInc.parse("thisitemtheme elite *" + epicchance * 20)
      );
      tf.themeincs.add(
        ThemeInc.parse("thisitemtheme sacred *" + epicchance * 50)
      );
      tf.themeincs.add(
        ThemeInc.parse("thisitemtheme not sacred *" + (1 / epicchance) * 0.50)
      );
      tf.themeincs.add(
        ThemeInc.parse("thisitemtheme not elite *" + (1 / epicchance) * 0.50)
      );
    } else {
      tf.themeincs.add(
        ThemeInc.parse("thisitemtheme elite *" + epicchance * 50)
      );
      tf.themeincs.add(
        ThemeInc.parse("thisitemtheme not elite *" + (1 / epicchance) * 0.50)
      );
      tf.themeincs.add(ThemeInc.parse("thisitemtheme sacred *0.05"));
    }

    if (!sacred) u.tags.addName("elite");
    if (sacred) {
      u.tags.addName("sacred");
      tf.commands.add(new Command("#holy"));

      // Make giants of size 7 and above holy cost 2
      if (u.getCommandValue("#size", 1) >= 7) {
        tf.commands.add(new Command("#holycost", new Arg(2)));
      }
    }
    u.appliedFilters.add(tf);

    // Equip
    unitGen.armorUnit(u, null, null, null, false);

    TroopTemplate t = TroopTemplate.getNew(
      u.getSlot("armor"),
      race,
      u,
      role,
      u.pose,
      this
    );

    if (role.equals("mounted")) this.armCavalry(u, t);
    else this.armInfantry(u, t);

    if (
      u.pose.getItems("offhand") != null &&
      u.pose.getItems("offhand").possibleItems() > 0 &&
      isDualWieldEligible(u) &&
      (u.getSlot("offhand") == null || u.getSlot("offhand").armor)
    ) {
      ItemSet items = fetchItems(u, "offhand", sacred, epicchance);
      ItemSet weaps = items.filterArmor(false);

      double dwchance = this.getDualWieldChance(u, 0.15);
      Item item = null;
      if (weaps.size() > 0 && random.nextDouble() < dwchance) {
        item = chandler.getRandom(weaps, u);
      }

      if (item != null) u.setSlot("offhand", item);
    }

    if (
      u.pose.getItems("bonusweapon") != null &&
      u.pose.getItems("bonusweapon").possibleItems() > 0
    ) {
      int prot = nationGen.armordb.GetInteger(u.getSlot("armor").id, "prot");
      double local_bwchance = 0.4 + this.getBonusWeaponChance(u);
      if (random.nextDouble() < local_bwchance - (double) prot * 0.02) {
        Item weapon = chandler.getRandom(
          fetchItems(u, "bonusweapon", sacred, epicchance),
          u
        );
        u.setSlot("bonusweapon", weapon);
      }
    }

    if (
      u.pose.getItems("cloakb") != null &&
      u.pose.getItems("cloakb").size() > 0 &&
      random.nextDouble() > epicchance / 8
    ) {
      Item weapon = chandler.getRandom(
        fetchItems(u, "cloakb", sacred, epicchance),
        u
      );
      u.setSlot("cloakb", weapon);
    }

    if (
      u.pose.roles.contains("ranged") ||
      (u.pose.roles.contains("sacred ranged") &&
        u.pose.getItems("bonusweapon") != null &&
        u.pose.getItems("bonusweapon").size() > 0)
    ) {
      Item weapon = chandler.getRandom(
        fetchItems(u, "bonusweapon", sacred, epicchance),
        u
      );
      u.setSlot("bonusweapon", weapon);
    }

    // Give magic weapons if they were promised:
    if (
      u.tags.containsName("NEEDSMAGICWEAPON") ||
      (chandler.identifier.equals("herogen") && random.nextDouble() > 0.15)
    ) {
      int cost = u.tags.getInt("NEEDSMAGICWEAPON").orElse(5);

      u.tags.remove("NEEDSMAGICWEAPON");

      customizeWeaponry(u, cost);
    }

    this.armSpecialSlot(u, null, usedItems);

    // Clean up
    cleanUnit(u);

    // Adjust gcost
    adjustGoldCost(u, sacred);

    return u;
  }

  private void adjustGoldCost(Unit u, boolean sacred) {
    if (
      u.pose.types.contains("ranged") ||
      u.pose.types.contains("elite ranged") ||
      u.pose.types.contains("sacred ranged")
    ) if (u.getGoldCost() < 15 && u.getResCost(true) < 15) u.commands.add(
      Command.args("#gcost", "+10")
    );

    int cgcost = u.getGoldCost();
    int costThreshold = u.getCommandValue("#size", 2) * 25;

    cgcost -= costThreshold;

    if (cgcost <= 0) {
      return;
    }

    int newprice = (int) Math.round(Math.pow(cgcost, 0.965));
    int discount = cgcost - newprice;

    if (
      u.isRanged() &&
      u.getSlot("mount") == null &&
      u.getGoldCost() - discount > (costThreshold * 0.8)
    ) {
      discount += (u.getGoldCost() - discount) / 5;
    }

    u.commands.add(Command.args("#gcost", "-" + discount));
  }

  private ItemSet fetchItems(
    Unit u,
    String slot,
    boolean sacred,
    double epicchance
  ) {
    ItemSet possibles = new ItemSet();

    if (u.pose.getItems(slot) == null) return possibles;

    if (possibles.size() == 0 && random.nextDouble() < 0.5) possibles =
      usedItems.filterForPose(u.pose).filterSlot(slot);
    if (
      possibles.size() == 0 || random.nextDouble() > epicchance * 1.5
    ) possibles = u.pose.getItems(slot);

    if (possibles.size() == 0) System.out.println(
      "No item in slot " +
      slot +
      " for a pose with roles " +
      u.pose.roles +
      " and race " +
      u.race.name
    );

    return possibles;
  }
}
