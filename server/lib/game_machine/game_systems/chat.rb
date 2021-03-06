require 'digest'
module GameMachine
  module GameSystems
    class Chat < Actor::Base
      include GameMachine::Commands

      def self.subscribers_for_topic(topic,game_id)
        datastore = GameMachine::Commands::DatastoreCommands.new
        if entity = datastore.get("#{game_id}_chat_topic_#{topic}")
          entity.subscribers || MessageLib::Subscribers.new
        else
          MessageLib::Subscribers.new
        end
      end

      attr_reader :chat_id, :registered_as, :game_id
      def post_init(*args)
        @chat_id = args[0]
        @registered_as = args[1]

        player_service = GameMachine::JavaLib::PlayerService.get_instance
        @game_id = player_service.get_game_id(chat_id)

        @topic_handlers = {}

        # Update these values from the datastore, as this state needs to
        # persist between restarts
        @subscriptions = get_subscriptions
        @channel_flags = get_flags

        # Make sure we re-create our state from the persisted state if we died
        load_state
        self.class.logger.debug "Chat child #{chat_id} started with game_id #{game_id}"
      end

      def load_state
        @subscriptions.each do |name|
          flags = flags_for_channel(name).join('|')
          join_channel(name,flags)
        end
      end

      def on_receive(entity)
        self.class.logger.debug "Received #{entity}"
        if entity.is_a?(MessageLib::ChatDestroy)
          leave_all_channels
          return
        end

        if entity.has_join_chat
          self.class.logger.debug "Join chat"
          join_channels(entity.join_chat.get_chat_channel_list)
          send_status
        end

        if entity.has_chat_message
          self.class.logger.debug "Chat message"
          send_message(entity.chat_message)
        end

        if entity.has_leave_chat
          self.class.logger.debug "Leave chat"
          leave_channels(entity.leave_chat.get_chat_channel_list)
          send_status
        end

        if entity.has_chat_status
          send_status
        end
      end

      # flags = subscribers
      def send_status
        channels = MessageLib::ChatChannels.new
        @subscriptions.each do |name|
          flags = @channel_flags.fetch(name,[])
          channel = MessageLib::ChatChannel.new.set_name(name)
          if flags.include?('subscribers')
            channel.set_subscribers(self.class.subscribers_for_topic(name,game_id))
          end
          channels.add_chat_channel(channel)
        end
        if registered_as == 'player'
          commands.player.send_message(channels,chat_id)
        else
          message = MessageLib::Entity.new.set_id(chat_id).set_chat_channels(channels)
          Actor::Base.find(registered_as).tell(message,get_self)
        end
      end

      def leave_all_channels
        channels = MessageLib::ChatChannels.new
        @subscriptions.each do |name|
          channel = MessageLib::ChatChannel.new.set_name(name)
          leave_channel(channel)
        end
        get_sender.tell('complete',get_self)
      end

      def message_queue
        MessageQueue.find
      end

      def topic_handler_for(name)
        @topic_handlers.fetch(name,nil)
      end

      def create_topic_handler(topic)
        name = "topic#{chat_id}#{topic}"
        builder = Actor::Builder.new(GameSystems::ChatTopic,chat_id,registered_as)
        actor_name = Digest::MD5.hexdigest(name)
        ref = builder.with_parent(context).with_name(actor_name).start
        actor_ref = Actor::Ref.new(ref,GameSystems::ChatTopic.name)
        @topic_handlers[topic] = actor_ref
      end

      def remove_subscriber(subscriber_id,topic)
        stored_entity_id = "#{game_id}_chat_topic_#{topic}"
        entity = MessageLib::Entity.new.set_id(subscriber_id)
        commands.datastore.call_dbproc(:chat_remove_subscriber,stored_entity_id,entity,false)
      end

      def add_subscriber(subscriber_id,topic)
        stored_entity_id = "#{game_id}_chat_topic_#{topic}"
        entity = MessageLib::Entity.new.set_id(subscriber_id)
        commands.datastore.call_dbproc(:chat_add_subscriber,stored_entity_id,entity,false)
      end

      def save_subscriptions
        entity_id = "subscriptions_#{chat_id}"
        channels = MessageLib::ChatChannels.new
        @subscriptions.each do |name|
          channel = MessageLib::ChatChannel.new.set_name(name)
          channels.add_chat_channel(channel)
        end
        entity = MessageLib::Entity.new.set_id(entity_id).set_chat_channels(channels)
        commands.datastore.put(entity)
      end

      def get_subscriptions
        subscriptions = Set.new
        entity_id = "subscriptions_#{chat_id}"
        if entity = commands.datastore.get(entity_id)
          if chat_channels = entity.chat_channels.get_chat_channel_list
            chat_channels.each do |channel|
              subscriptions.add(channel.name)
            end
          end
        end
        subscriptions
      end

      def parse_channel_flags(flags)
        flags.split('|')
      end

      def channel_flags_id(name)
        "channel_flags#{@chat_id}#{name}"
      end

      def flags_for_channel(channel_name)
        @channel_flags.fetch(channel_name,[])
      end

      def get_flags
        {}.tap do |flags|
          @subscriptions.each do |name|
            if entity = commands.datastore.get(channel_flags_id(name))
              flags[name] = parse_channel_flags(entity.params)
            end
          end
        end
      end

      def set_channel_flags(name,flags)
        return if flags.nil?
        @channel_flags[name] = parse_channel_flags(flags)
        flags_id = channel_flags_id(name)
        entity = MessageLib::Entity.new.set_id(flags_id).set_params(flags)
        commands.datastore.put(entity)
      end

      def delete_channel_flags(channel_name)
        flags_id = channel_flags_id(channel_name)
        commands.datastore.delete(flags_id)
      end

      def invite_exists?(channel_name,invite_id)
        key = "invite_#{channel_name}_#{invite_id}"
        commands.datastore.get(key)
      end

      def join_channels(chat_channels)
        chat_channels.each do |channel|
          if @topic_handlers[channel.name]
            self.class.logger.info "Topic handler exists for #{channel.name}, not creating"
            next
          end

          self.class.logger.debug "Join request #{channel.name}"
          # Private channels.  format priv_[chat_id]_[channel name]
          # Players can create private channels with their player id, other
          # players must have an invite to join someone elses private channel
          if channel.name.match(/^priv/)
            if channel.name.match(/^priv_#{chat_id}/)
              join_channel(channel.name,channel.flags)
            elsif channel.invite_id
              if invite_exists?(channel.name,channel.invite_id)
                join_channel(channel.name,channel.flags)
              else
                self.class.logger.info "Invite id #{channel.invite_id} not found"
              end
            end
          else
            join_channel(channel.name,channel.flags)
          end
        end
      end

      def join_channel(name,flags)
        create_topic_handler(name)
        message = MessageLib::Subscribe.new.set_topic(name).set_game_id(game_id)
        message_queue.tell(message,topic_handler_for(name).actor)
        @subscriptions.add(name)
        save_subscriptions
        add_subscriber(chat_id,name)
        set_channel_flags(name,flags)
        self.class.logger.debug "Player #{chat_id} Joined channel #{name} with flags #{flags}"
      end

      def leave_channels(chat_channels)
        chat_channels.each do |channel|
          leave_channel(channel)
        end
      end

      def leave_channel(channel)
        if topic_handler = topic_handler_for(channel.name)
          message = MessageLib::Unsubscribe.new.set_topic(channel.name).set_game_id(game_id)
          message_queue.tell(message,topic_handler_for(channel.name).actor)
          @subscriptions.delete_if {|sub| sub == channel.name}
          save_subscriptions
          remove_subscriber(chat_id,channel.name)
          topic_handler.tell(JavaLib::PoisonPill.get_instance)
          @topic_handlers.delete(channel.name)
          delete_channel_flags(channel.name)
        else
          self.class.logger.info "leave_channel: no topic handler found for #{channel.name}"
        end
      end

      def send_private_message(chat_message)
        self.class.logger.debug "Sending private chat message #{chat_message.message} from:#{chat_id} to:#{chat_message.chat_channel.name}"
        unless can_send_to_player?(chat_id,chat_message.chat_channel.name)
          self.class.logger.info "Private chat denied, mismatched game id"
          return
        end

        entity = MessageLib::Entity.new
        player = MessageLib::Player.new.set_id(chat_message.chat_channel.name)
        entity.set_id(player.id)
        entity.set_player(player)
        entity.set_chat_message(chat_message)
        entity.set_send_to_player(true)
        commands.player.send_message(entity,player.id)
        self.class.logger.debug "Sent private chat message #{chat_message.message} from:#{chat_id} to:#{chat_message.chat_channel.name}"
      end

      def can_send_to_player?(sender_id,recipient_id)
        player_service = JavaLib::PlayerService.get_instance
        if recipient_player = player_service.find(recipient)
          if recipient_player.get_game_id == game_id
            return true
          end
        end
        false
      end

      def send_group_message(chat_message)
        topic = chat_message.chat_channel.name
        if topic_handler = topic_handler_for(topic)
          entity = MessageLib::Entity.new.set_id('0').set_chat_message(chat_message)
          publish = MessageLib::Publish.new
          publish.set_topic(topic).set_message(entity).set_game_id(game_id)
          message_queue.tell(publish,topic_handler_for(topic).actor)
        else
          self.class.logger.info "send_message: no topic handler found for #{topic}"
        end
      end

      def send_message(chat_message)
        if chat_message.type == 'group'
          send_group_message(chat_message)
        elsif chat_message.type == 'private'
          send_private_message(chat_message)
        end
      end

    end
  end
end
