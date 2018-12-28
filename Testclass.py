# -*- coding:utf-8 -*-

class test(object):
    def __init__(self, c):
        self.a = 2
        self.b = c
        
    def hello(self):
        print(self.a)
        print(self.b)
        
 
hello = test("nihao")
hello.hello()
