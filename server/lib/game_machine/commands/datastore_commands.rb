module GameMachine
  module Commands
    class DatastoreCommands

      attr_reader :store
      def initialize
        @store = DataStore.instance
      end

      def define_dbproc(name,&blk)
        ObjectDb.dbprocs[name] = blk
      end

      def call_dbproc(name,current_entity_id,update_entity,blocking=true)
        ref = ObjectDb.find_distributed(current_entity_id)
        message = MessageLib::ObjectdbUpdate.new.
          set_current_entity_id(current_entity_id).
          set_update_method(name).
          set_update_entity(update_entity)
        if blocking
          ref.ask(message, 100)
        else
          ref.tell(message)
        end
      end

      def put_direct(entity)
        store.put(entity.id,entity.to_byte_array)
      end

      def get_direct(entity_id)
        if bytes = store.get(entity_id)
          MessageLib::Entity.parse_from(bytes)
        else
          nil
        end
      end

      def del_direct(entity_id)
        store.delete(entity_id)
      end

      def put(entity)
        ref = ObjectDb.find_distributed(entity.get_id)
        ref.tell(MessageLib::ObjectdbPut.new.set_entity(entity))
      end

      def get(entity_id,timeout=1000)
        ref = ObjectDb.find_distributed(entity_id)
        ref.ask(MessageLib::ObjectdbGet.new.set_entity_id(entity_id), timeout)
      end

      def delete(entity_id)
        ref = ObjectDb.find_distributed(entity_id)
        ref.tell(MessageLib::ObjectdbDel.new.set_entity_id(entity_id))
      end

    end
  end
end
