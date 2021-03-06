
# We use this for player specific maintenance, such as regenerating health
module Example
  class PlayerManager < GameMachine::Actor::Base
    include Models
    include GameMachine::Commands

    attr_reader :player_ids
    def post_init(*args)
      @player_ids = []
      update_player_ids
      schedule_message('update',5,:seconds)
    end

    # All players currently logged into this node
    def update_player_ids
      @player_ids = GameMachine::ClientManager.local_connections.keys
    end

    def regen_player_health
      update_player_ids
      player_ids.each do |player_id|
        if vitals = Vitals.find(player_id)
          vitals.health += 5
          if vitals.health > vitals.max_health
            vitals.health = vitals.max_health
          end
          if grid_value = commands.grid.find_by_id(player_id)
            vitals.x = grid_value.x
            vitals.y = grid_value.y
          end
          vitals.save
          commands.player.send_message(vitals,player_id)
        end
      end
    end

    def on_receive(message)
      if message.is_a?(String)
        if message == 'update'
          regen_player_health
        end
      elsif message.is_a?(GameMachine::Models::PlayerStatusUpdate)
        if vitals = Vitals.find(message.player_id)
          if message.status == 'registered'
            if zone = GameMachine::GameSystems::RegionService.region
              vitals.zone = zone
              vitals.save
              GameMachine.logger.info "#{message.player_id} entered zone #{vitals.zone}"
            end
          elsif message.status == 'unregistered'
          end
        end
      end
    end

  end
end
