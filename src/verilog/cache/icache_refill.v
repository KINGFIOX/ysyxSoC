import "DPI-C" function void icache_refill(input int addr, input int data);

           module icache_refill #(
                   parameter ADDR_BITS = 32,
                   parameter DATA_BITS = 32
               ) (
                   input clock,
                   input valid_w,
                   input [ADDR_BITS-1:0] addr_w,
                   input [DATA_BITS-1:0] data_w
               );

               always_ff @(posedge clock) begin
                   if (valid_w) begin
                       icache_refill(addr_w, data_w);
                   end
               end

           endmodule
