class AddServerManagementOptions < ActiveRecord::Migration
  def change
    add_column :servers, :status, :string
  end
end