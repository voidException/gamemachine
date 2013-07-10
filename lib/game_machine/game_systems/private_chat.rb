module GameMachine
  module GameSystems
    class PrivateChat < Actor

      def preStart
        message = Subscribe.new.set_topic('private')
        MessageQueuel.find.tell(message,ActorRef.new(get_self))
      end

      def on_receive(message)
        if message.is_a?(ChatMessage)
          send_message(message)
        elsif message.is_a?(JavaLib::DistributedPubSubMediator::SubscribeAck)
        else
          unhandled(message)
        end
      end
    end
  end
end
