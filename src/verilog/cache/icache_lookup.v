import "DPI-C" function void icache_lookup(input int addr, output int hit, output int data);

           module icache_lookup #(
                   parameter ADDR_BITS = 32,
                   parameter DATA_BITS = 32
               ) (
                   input clock,
                   input req_w,
                   input [ADDR_BITS-1:0] addr_w,
                   output reg hit_q,
                   output reg [DATA_BITS-1:0] data_q
               );

               always_ff @(posedge clock) begin
                   hit_q <= 0; // default
                   if (req_w) begin
                       automatic logic [31:0] hit_w;
                       icache_lookup(addr_w, hit_w, data_q);
                       hit_q <= |hit_w; // reduce to 1 bit
                   end
               end

           endmodule
